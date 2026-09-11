package com.fsaint.androidagent.mcp

import com.fsaint.androidagent.model.*
import com.fsaint.androidagent.policy.ResourceType
import com.fsaint.androidagent.policy.ScopeRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

const val MCP_ARGUMENTS_KEY = "_mcp_arguments_json"
fun canonicalMcpJson(value: JsonElement): String {
    fun sorted(node: JsonElement): JsonElement = when (node) {
        is JsonObject -> JsonObject(node.toSortedMap().mapValues { sorted(it.value) })
        is JsonArray -> JsonArray(node.map(::sorted))
        else -> node
    }
    return sorted(value).toString()
}
data class McpBinding(val connection: McpConnection, val tool: LiveMcpTool, val generation: Long)
data class McpServerInventory(val id: String, val name: String, val status: String, val toolCount: Int, val message: String)
data class McpSnapshot(val tools: Map<String, McpBinding>, val inventory: List<String>, val servers: List<McpServerInventory> = emptyList())
data class McpConnectionState(val status: String = "SAVED", val checkedAt: Long? = null, val toolNames: List<String> = emptyList(), val message: String = "Saved; not checked.")

class McpConnectionManager(
    private val configurations: suspend () -> List<McpConnection>,
    private val scopes: ScopeRegistry,
    private val client: LiveMcpClient,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Cached(val configuration: McpConnection, val discovery: McpDiscovery, val at: Long, val generation: Long)
    private val cache = ConcurrentHashMap<String, Cached>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val generations = ConcurrentHashMap<String, AtomicLong>()
    private val state = MutableStateFlow<Map<String, McpConnectionState>>(emptyMap())
    private val publicationLock = Any()
    val states = state.asStateFlow()
    private fun generation(id: String) = generations.computeIfAbsent(id) { AtomicLong() }.get()
    fun invalidate(id: String) = synchronized(publicationLock) { generations.computeIfAbsent(id) { AtomicLong() }.incrementAndGet(); cache.remove(id); state.update { it - id } }
    private fun publishFailure(id: String, version: Long, error: Exception) = synchronized(publicationLock) {
        if (generation(id) == version) {
            cache.remove(id)
            val failure = error as? McpFailure
            state.update { it + (id to McpConnectionState(failure?.kind ?: "UNREACHABLE", clock(), message = failure?.detail ?: "Request interrupted or unavailable. Refresh to try again.")) }
        }
    }

    suspend fun snapshot(session: ScopedAgentSession): McpSnapshot {
        val configs = configurations().filter { scopes.permits(session, ResourceType.MCP, it.id) }
        val semaphore = Semaphore(4)
        withTimeoutOrNull(20_000) {
            supervisorScope { configs.map { config -> async { semaphore.withPermit { load(config) } } }.awaitAll() }
        }
        val tools = linkedMapOf<String, McpBinding>(); val inventory = mutableListOf<String>()
        val servers = mutableListOf<McpServerInventory>()
        val current = configurations().associateBy { it.id }
        for (config in configs) {
            if (current[config.id] != config || !scopes.permits(session, ResourceType.MCP, config.id)) continue
            val cached = cache[config.id]?.takeIf { it.configuration == config && it.generation == generation(config.id) && clock() - it.at < 300_000 }
            val status = state.value[config.id] ?: McpConnectionState()
            inventory += "${config.displayName.take(80)}: ${status.message}"
            servers += McpServerInventory(config.id, config.displayName.take(80), status.status, cached?.discovery?.tools?.size ?: 0, status.message)
            cached?.discovery?.tools?.forEach { tool ->
                if (tools.size < 128) {
                    val alias = alias(config.id, tool.name)
                    val binding = McpBinding(config, tool, cached.generation)
                    check(tools[alias] == null || tools[alias] == binding) { "MCP tool alias collision" }
                    tools[alias] = binding
                }
            }
        }
        if (configs.sumOf { cache[it.id]?.discovery?.tools?.size ?: 0 } > 128) inventory += "Remote tool inventory limited to 128 tools."
        return McpSnapshot(tools, inventory, servers)
    }

    suspend fun refresh(session: ScopedAgentSession, id: String) {
        require(scopes.permits(session, ResourceType.MCP, id))
        val config = configurations().firstOrNull { it.id == id } ?: return
        invalidate(id)
        withTimeoutOrNull(20_000) { load(config) }
    }

    private suspend fun load(config: McpConnection): Cached? = locks.computeIfAbsent(config.id) { Mutex() }.withLock {
        cache[config.id]?.takeIf { it.configuration == config && it.generation == generation(config.id) && clock() - it.at < 300_000 && !it.discovery.session.toolsChanged }?.let { return@withLock it }
        val version = generation(config.id)
        val before = configurations()
        synchronized(publicationLock) {
            if (generation(config.id) != version || before.none { it == config }) return@withLock null
            state.update { it + (config.id to McpConnectionState("CONNECTING", message = "Connecting…")) }
            cache.remove(config.id)
        }
        try {
            val discovered = client.discover(config.endpoint)
            val current = configurations()
            synchronized(publicationLock) {
                if (generation(config.id) != version || current.none { it == config }) return@withLock null
                val cached = Cached(config, discovered, clock(), version)
                cache[config.id] = cached
                state.update { it + (config.id to McpConnectionState(if (discovered.tools.isEmpty()) "NO_TOOLS" else "READY", clock(), discovered.tools.map { tool -> tool.name },
                    (if (discovered.tools.isEmpty()) "No usable tools." else "Ready: ${discovered.tools.size} tools.") + discovered.notices.distinct().joinToString(" ", prefix = " "))) }
                cached
            }
        } catch (error: Exception) {
            // No suspending reads in cancellation cleanup. Removal increments the generation atomically.
            publishFailure(config.id, version, error)
            if (error is CancellationException) throw error
            null
        }
    }

    suspend fun execute(session: ScopedAgentSession, snapshot: McpSnapshot, call: ToolCall): ToolResult<Any> {
        val binding = snapshot.tools[call.name] ?: return ToolResult(false, error = ToolError.NOT_FOUND)
        val id = binding.connection.id
        if (!scopes.permits(session, ResourceType.MCP, id)) return ToolResult(false, error = ToolError.SCOPE_DENIED)
        return locks.computeIfAbsent(id) { Mutex() }.withLock {
            val current = configurations()
            val cached = synchronized(publicationLock) {
                cache[id]?.takeIf { generation(id) == binding.generation && current.any { config -> config == binding.connection } && it.configuration == binding.connection && it.generation == binding.generation && it.discovery.tools.any { tool -> tool == binding.tool } }
            } ?: return@withLock ToolResult(false, error = ToolError.NOT_FOUND)
            if (!scopes.permits(session, ResourceType.MCP, id)) return@withLock ToolResult(false, error = ToolError.SCOPE_DENIED)
            val args = try { parseMcpObject(requireNotNull(call.arguments[MCP_ARGUMENTS_KEY])) } catch (_: Exception) { return@withLock ToolResult(false, error = ToolError.FAILED, payload = "Invalid MCP arguments.") }
            currentCoroutineContext().ensureActive()
            try {
                val result = client.call(cached.discovery.session, binding.tool.name, args)
                validateResult(result)
                val failed = (result["isError"] as? JsonPrimitive)?.booleanOrNull == true
                val parts = (result["content"] as? JsonArray).orEmpty().mapNotNull { part ->
                    val block = part as? JsonObject ?: return@mapNotNull "Unsupported result block."
                    when ((block["type"] as? JsonPrimitive)?.contentOrNull) {
                        "text" -> (block["text"] as? JsonPrimitive)?.contentOrNull
                        "image", "audio" -> "Binary MCP result received; delivery is unsupported in this release."
                        "resource_link", "resource" -> "MCP resource result received; it was not fetched."
                        else -> "Unsupported MCP result block."
                    }
                }
                val text = (parts + listOfNotNull(result["structuredContent"]?.toString())).joinToString("\n")
                val bounded = text.take(24_000) + if (text.length > 24_000) "\n[Result truncated]" else ""
                ToolResult(!failed, payload = bounded.ifBlank { "Tool returned no text content." }, error = if (failed) ToolError.FAILED else null, verification = VerificationState.VERIFIED)
            } catch (cancelled: CancellationException) { publishFailure(id, binding.generation, cancelled); throw cancelled }
            catch (error: Exception) {
                publishFailure(id, binding.generation, error)
                val failure = error as? McpFailure
                ToolResult(false, error = if (failure?.kind == "AUTH_REQUIRED") ToolError.PERMISSION_REQUIRED else ToolError.NETWORK_ERROR,
                    payload = (failure?.detail ?: "MCP request failed.") + " A dispatched action may have completed. Do not automatically repeat it.")
            }
        }
    }

    private fun validateResult(result: JsonObject) {
        fun invalid(): Nothing = throw McpFailure("PROTOCOL_ERROR", "MCP tool returned a malformed result; outcome unknown.")
        fun string(node: JsonElement?) = node is JsonPrimitive && node.isString
        val content = result["content"] as? JsonArray ?: invalid()
        if (result.containsKey("isError")) {
            val value = result["isError"] as? JsonPrimitive ?: invalid()
            if (value.isString || value.booleanOrNull == null) invalid()
        }
        if (result.containsKey("structuredContent") && result["structuredContent"] !is JsonObject) invalid()
        content.forEach { node ->
            val block = node as? JsonObject ?: invalid()
            if (!string(block["type"])) invalid()
            when (block["type"]!!.jsonPrimitive.content) {
                "text" -> if (!string(block["text"])) invalid()
                "image", "audio" -> if (!string(block["data"]) || !string(block["mimeType"])) invalid()
                "resource_link" -> if (!string(block["uri"]) || !string(block["name"])) invalid()
                "resource" -> {
                    val resource = block["resource"] as? JsonObject ?: invalid()
                    if (!string(resource["uri"]) || (!string(resource["text"]) && !string(resource["blob"]))) invalid()
                }
                // Future block types remain an explicit unsupported-content notice.
            }
        }
    }

    private fun alias(id: String, name: String): String = "mcp_" + MessageDigest.getInstance("SHA-256").digest("$id\u0000$name".toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }.take(48)
}
