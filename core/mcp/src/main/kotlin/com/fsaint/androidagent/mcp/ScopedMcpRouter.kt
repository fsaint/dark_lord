package com.fsaint.androidagent.mcp

import com.fsaint.androidagent.model.ScopedAgentSession
import com.fsaint.androidagent.model.ToolError
import com.fsaint.androidagent.policy.ResourceType
import com.fsaint.androidagent.policy.ScopeRegistry

class ScopedMcpRouter(
    private val scopes: ScopeRegistry,
    connections: Collection<McpConnection>,
    private val handler: suspend (McpConnection, McpToolCall) -> McpCallResult,
) {
    private val connections = connections.associateBy { it.id }

    fun discover(session: ScopedAgentSession): List<McpConnection> = connections.values
        .filter { scopes.permits(session, ResourceType.MCP, it.id) }
        .map { it.copy(endpoint = "") }

    suspend fun execute(session: ScopedAgentSession, call: McpToolCall): McpToolResult {
        if (!scopes.permits(session, ResourceType.MCP, call.connectionId)) return McpToolResult(false, error = ToolError.SCOPE_DENIED)
        val connection = connections[call.connectionId] ?: return McpToolResult(false, error = ToolError.NOT_FOUND)
        if (connection.tools.isNotEmpty() && connection.tools.none { it.name == call.name }) {
            return McpToolResult(false, error = ToolError.NOT_FOUND)
        }
        return McpToolResult.from(runCatching { handler(connection, call) }.getOrElse { McpCallResult.NetworkError(it.message) })
    }
}
