package com.fsaint.androidagent.mcp

import com.fsaint.androidagent.model.ToolError

data class McpHttpRequest(val url: String, val headers: Map<String, String> = emptyMap(), val body: String = "")
data class McpHttpResponse(val status: Int, val body: String)
interface McpHttpTransport { suspend fun execute(request: McpHttpRequest): McpHttpResponse }

class StreamableHttpMcpClient(
    private val transport: McpHttpTransport,
    private val maxBodyBytes: Int = 256 * 1024,
) {
    suspend fun request(connection: McpConnection, call: McpToolCall, token: OAuthToken? = null): McpToolResult {
        if (!connection.endpoint.startsWith("https://")) return McpToolResult(false, error = ToolError.NETWORK_ERROR)
        val body = "{\"method\":\"tools/call\",\"name\":\"${escape(call.name)}\",\"arguments\":{${call.arguments.entries.joinToString(",") { "\"${escape(it.key)}\":\"${escape(it.value)}\"" }}}}"
        if (body.toByteArray().size > maxBodyBytes) return McpToolResult(false, error = ToolError.NETWORK_ERROR)
        val headers = buildMap { put("Content-Type", "application/json"); token?.let { put("Authorization", "Bearer ${it.accessToken}") } }
        val response = runCatching { transport.execute(McpHttpRequest(connection.endpoint, headers, body)) }.getOrElse { return McpToolResult(false, error = ToolError.NETWORK_ERROR) }
        if (response.body.toByteArray().size > maxBodyBytes || response.status !in 200..299) return McpToolResult(false, error = ToolError.NETWORK_ERROR)
        return McpToolResult(true, payload = response.body)
    }

    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"").take(4096)
}
