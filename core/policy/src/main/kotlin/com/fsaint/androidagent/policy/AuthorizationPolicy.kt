package com.fsaint.androidagent.policy

import com.fsaint.androidagent.model.PrincipalRole
import com.fsaint.androidagent.model.ScopedAgentSession
import com.fsaint.androidagent.model.ToolCall
import com.fsaint.androidagent.model.ToolError
import com.fsaint.androidagent.model.ToolResult
import com.fsaint.androidagent.model.VerificationState

enum class ResourceType { TOOL, MCP, MEMORY, SKILL }

data class Principal(val id: String, val e164: String?, val role: PrincipalRole)

class PrincipalRegistry(principals: Iterable<Principal> = emptyList(), private val defaultCountryCode: String = "+1") {
    private val byPhone = principals.mapNotNull { principal -> principal.e164?.let(::normalize)?.let { it to principal } }.toMap()
    fun lookup(phoneNumber: String?): Principal? = phoneNumber?.let(::normalize)?.let(byPhone::get)
    fun normalize(phoneNumber: String): String {
        val digits = phoneNumber.filter(Char::isDigit)
        return when {
            phoneNumber.trim().startsWith("+") -> "+$digits"
            digits.length == 10 -> "$defaultCountryCode$digits"
            else -> digits
        }
    }
}

class ScopeRegistry {
    private val grants = mutableMapOf<String, MutableSet<Pair<ResourceType, String>>>()

    fun grant(principalId: String, type: ResourceType, resource: String) {
        grants.getOrPut(principalId) { mutableSetOf() }.add(type to resource)
    }

    fun permits(session: ScopedAgentSession, type: ResourceType, resource: String): Boolean = when (session.role) {
        PrincipalRole.OWNER -> true
        else -> (type to resource) in defaults(session.role) || (type to resource) in grants[session.principalId].orEmpty()
    }

    fun resourcesFor(session: ScopedAgentSession, type: ResourceType): Set<String> =
        (defaults(session.role) + grants[session.principalId].orEmpty()).filter { it.first == type }.mapTo(mutableSetOf()) { it.second }

    fun sessionFor(principal: Principal, channel: String): ScopedAgentSession = ScopedAgentSession(
        id = "${principal.id}:$channel",
        principalId = principal.id,
        role = principal.role,
        scopeId = principal.role.name.lowercase(),
        channel = channel,
        memoryNamespace = when (principal.role) { PrincipalRole.UNKNOWN -> "anonymous/${principal.id}"; else -> principal.id },
        createdAtEpochMs = 0,
    )

    private fun defaults(role: PrincipalRole): Set<Pair<ResourceType, String>> = when (role) {
        PrincipalRole.UNKNOWN -> setOf(ResourceType.TOOL to "sms.reply", ResourceType.TOOL to "owner.notify", ResourceType.MEMORY to "current_session", ResourceType.SKILL to "message-taking")
        else -> emptySet()
    }
}

data class AgentContext(val resources: Set<String>, val memory: Map<String, List<String>>)

class ScopedContextBuilder(private val scopes: ScopeRegistry, private val memory: Map<String, List<String>>) {
    fun build(session: ScopedAgentSession): AgentContext {
        val allowedMemory = memory.filterKeys { scopes.permits(session, ResourceType.MEMORY, it) }
        val resources = buildSet {
            listOf(ResourceType.TOOL, ResourceType.MCP, ResourceType.SKILL).forEach { addAll(scopes.resourcesFor(session, it)) }
        }
        return AgentContext(resources, allowedMemory)
    }
}

class ScopedToolRouter(private val tools: Map<String, suspend (ToolCall) -> Any?>, private val scopes: ScopeRegistry = ScopeRegistry()) {
    suspend fun execute(session: ScopedAgentSession, call: ToolCall): ToolResult<Any> {
        if (!scopes.permits(session, ResourceType.TOOL, call.name)) return ToolResult(false, error = ToolError.SCOPE_DENIED)
        val tool = tools[call.name] ?: return ToolResult(false, error = ToolError.NOT_FOUND)
        @Suppress("UNCHECKED_CAST") return ToolResult(true, payload = tool(call) as Any, verification = VerificationState.UNVERIFIED)
    }
}

class ScopedMcpRouter(private val scopes: ScopeRegistry, private val connections: Set<String>) {
    suspend fun call(session: ScopedAgentSession, connection: String): ToolResult<Any> =
        if (!scopes.permits(session, ResourceType.MCP, connection)) ToolResult(false, error = ToolError.SCOPE_DENIED)
        else if (connection !in connections) ToolResult(false, error = ToolError.NOT_FOUND) else ToolResult(true, Unit)
}

class ScopedMemoryProvider(private val scopes: ScopeRegistry, private val memory: Map<String, List<String>>) {
    fun read(session: ScopedAgentSession, namespace: String): List<String> = if (scopes.permits(session, ResourceType.MEMORY, namespace)) memory[namespace].orEmpty() else emptyList()
}

class ScopedSkillRegistry(private val scopes: ScopeRegistry, private val skills: Set<String>) {
    fun availableFor(session: ScopedAgentSession): Set<String> = skills.filterTo(mutableSetOf()) { scopes.permits(session, ResourceType.SKILL, it) }
}
