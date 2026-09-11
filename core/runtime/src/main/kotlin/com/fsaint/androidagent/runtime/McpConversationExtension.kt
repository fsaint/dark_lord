package com.fsaint.androidagent.runtime

import com.fsaint.androidagent.mcp.McpConnectionManager
import com.fsaint.androidagent.model.*
import com.fsaint.androidagent.policy.RemoteToolDefinition
import kotlinx.serialization.json.*

const val MCP_INVENTORY_TOOL = "mcp_inventory"

data class ConversationToolSnapshot(
    val definitions: Map<String, RemoteToolDefinition> = emptyMap(),
    val inventory: List<String> = emptyList(),
    val execute: suspend (ToolCall) -> ToolResult<Any> = { ToolResult(false, error = ToolError.NOT_FOUND) },
)
interface ConversationToolExtension { suspend fun prepare(session: ScopedAgentSession): ConversationToolSnapshot }

class McpConversationExtension(private val manager: McpConnectionManager) : ConversationToolExtension {
    override suspend fun prepare(session: ScopedAgentSession): ConversationToolSnapshot {
        val snapshot = manager.snapshot(session)
        val definitions = linkedMapOf(MCP_INVENTORY_TOOL to RemoteToolDefinition(MCP_INVENTORY_TOOL,
            "List configured MCP servers, their actual connection status, and discovered tool names. Use this read-only tool whenever asked which MCP servers/tools are available or whether MCP works. This inventory is provided by the app, not a remote server.",
            """{"type":"object","properties":{},"additionalProperties":false}"""))
        definitions.putAll(snapshot.tools.mapValues { (alias, binding) ->
            RemoteToolDefinition(alias, "MCP server ${binding.connection.displayName.take(80)} / ${binding.tool.name}: ${binding.tool.description}", binding.tool.inputSchema.toString())
        })
        return ConversationToolSnapshot(definitions, snapshot.inventory) { call ->
            if (call.name == MCP_INVENTORY_TOOL) {
                // Revalidate access and removal; do not expose a stale authorized snapshot.
                val current = manager.snapshot(session)
                val inventory = buildJsonObject {
                    put("servers", buildJsonArray { current.servers.forEach { server -> add(buildJsonObject {
                        put("id", server.id); put("name", server.name); put("status", server.status)
                        put("toolCount", server.toolCount); put("message", server.message)
                    }) } })
                    put("tools", buildJsonArray { current.tools.forEach { (alias, binding) -> add(buildJsonObject {
                        put("serverId", binding.connection.id); put("serverName", binding.connection.displayName.take(80))
                        put("name", binding.tool.name); put("alias", alias)
                    }) } })
                    put("notes", buildJsonArray {
                        add("Only tools advertised in this conversation can be called. Server counts can exceed the bounded advertised catalog.")
                        current.inventory.filter { it.contains("limited to") }.forEach { add(it) }
                    })
                }
                ToolResult(true, payload = inventory.toString(), verification = VerificationState.VERIFIED)
            } else manager.execute(session, snapshot, call)
        }
    }
}
