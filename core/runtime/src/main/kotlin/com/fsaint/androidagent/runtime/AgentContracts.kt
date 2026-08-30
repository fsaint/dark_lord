package com.fsaint.androidagent.runtime

import com.fsaint.androidagent.model.AgentEvent
import com.fsaint.androidagent.model.ScopedAgentSession
import com.fsaint.androidagent.model.ToolCall
import com.fsaint.androidagent.model.ToolError

data class AgentRequest(
    val runId: String,
    val session: ScopedAgentSession,
    val event: AgentEvent,
    val userText: String,
) { init { require(runId.isNotBlank()) } }

data class ModelRequest(
    val request: AgentRequest,
    val transcript: ConversationTranscript = ConversationTranscript(),
    val memory: Map<String, List<String>> = emptyMap(),
    val skills: List<SkillDefinition> = emptyList(),
    val tools: List<ToolDefinition> = emptyList(),
)

sealed interface ModelResponse {
    data class ToolCalls(val calls: List<ToolCall>) : ModelResponse
    data class Final(val text: String) : ModelResponse
    data class Escalate(val question: String, val reason: String) : ModelResponse
}

class ScopeSnapshot(val session: ScopedAgentSession, resources: Set<String> = emptySet(), val id: String = session.scopeId) {
    private val resourceSnapshot = resources.toSet()
    val resources: Set<String> get() = resourceSnapshot
    init { require(id.isNotBlank()) }
}

data class SkillDefinition(
    val id: String,
    val version: String = "1",
    val instructions: String = "",
    val examples: List<String> = emptyList(),
    val references: List<String> = emptyList(),
) { init { require(id.isNotBlank()); require(version.isNotBlank()) } }

enum class Confirmation { NONE, USER_CONFIRMATION_REQUIRED, OWNER_APPROVAL_REQUIRED }

enum class AgentRunState { FINAL, TOOL_CALL, ESCALATE, CANCELLED, TURN_LIMIT, FAILED }

object AgentRunStateCodec {
    private const val VERSION = "v1"
    fun encode(state: AgentRunState): String = "$VERSION:${when (state) {
        AgentRunState.FINAL -> "final"
        AgentRunState.TOOL_CALL -> "tool_call"
        AgentRunState.ESCALATE -> "escalate"
        AgentRunState.CANCELLED -> "cancelled"
        AgentRunState.TURN_LIMIT -> "turn_limit"
        AgentRunState.FAILED -> "failed"
    }}"
    fun decode(encoded: String): AgentRunState = when (encoded) {
        "v1:final" -> AgentRunState.FINAL
        "v1:tool_call" -> AgentRunState.TOOL_CALL
        "v1:escalate" -> AgentRunState.ESCALATE
        "v1:cancelled" -> AgentRunState.CANCELLED
        "v1:turn_limit" -> AgentRunState.TURN_LIMIT
        "v1:failed" -> AgentRunState.FAILED
        else -> throw IllegalArgumentException("Unknown agent run state wire tag")
    }
}

data class AgentRunResult(
    val runId: String,
    val state: AgentRunState,
    val response: String? = null,
    val error: ToolError? = null,
) { init { require(runId.isNotBlank()) } }

interface AgentHarness {
    suspend fun run(request: AgentRequest): AgentRunResult
    suspend fun resume(runId: String): AgentRunResult
    /** Requests cancellation and completes after the durable cancellation checkpoint is written. */
    suspend fun cancel(runId: String)

    companion object {
        const val MAX_TURNS = 8
        const val MAX_PARALLEL_TOOL_CALLS = 4
    }
}
