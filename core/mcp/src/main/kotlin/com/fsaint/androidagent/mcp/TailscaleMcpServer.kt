package com.fsaint.androidagent.mcp

import com.fsaint.androidagent.model.ScopedAgentSession
import com.fsaint.androidagent.model.ToolError
import java.net.InetAddress

interface TailscaleMcpListener {
    fun bind(host: String, port: Int, handler: suspend (String, String, String) -> McpToolResult): CloseableListener
}

interface CloseableListener { fun close() }

class TailscaleMcpServer(
    private val router: ScopedMcpRouter,
    private val enrolledClients: Map<String, ScopedAgentSession>,
    private val bindHost: String = "100.100.100.100",
    private val maxRequestBytes: Int = 64 * 1024,
    private val maxResponseBytes: Int = 256 * 1024,
) {
    fun bind(listener: TailscaleMcpListener, port: Int): CloseableListener = listener.bind(bindHost, port, ::handle)

    suspend fun handle(remoteAddress: String, clientId: String, request: String): McpToolResult {
        if (!isTailscaleAddress(remoteAddress)) return McpToolResult(false, error = ToolError.OS_RESTRICTED)
        val session = enrolledClients[clientId] ?: return McpToolResult(false, error = ToolError.PERMISSION_REQUIRED)
        if (request.toByteArray().size > maxRequestBytes) return McpToolResult(false, error = ToolError.NETWORK_ERROR)
        val call = parse(request) ?: return McpToolResult(false, error = ToolError.NOT_FOUND)
        val result = router.execute(session, call)
        if (result.payload?.toString()?.toByteArray()?.size ?: 0 > maxResponseBytes) return McpToolResult(false, error = ToolError.NETWORK_ERROR)
        return result
    }

    private fun parse(request: String): McpToolCall? {
        fun field(name: String): String? = Regex("\\\"$name\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").find(request)?.groupValues?.get(1)
        val connection = field("connection") ?: return null
        val tool = field("tool") ?: return null
        return McpToolCall(connection, tool)
    }

    private fun isTailscaleAddress(raw: String): Boolean = runCatching {
        val bytes = InetAddress.getByName(raw).address
        bytes.size == 4 && bytes[0].toInt() and 0xff == 100 && bytes[1].toInt() and 0xff in 64..127
    }.getOrDefault(false)
}
