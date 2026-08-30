package com.fsaint.androidagent.mcp

import com.fsaint.androidagent.model.ToolError

data class McpConnection(
    val id: String,
    val displayName: String,
    val endpoint: String,
    val tools: List<McpToolDescriptor> = emptyList(),
)

data class McpToolDescriptor(val name: String, val description: String)
data class McpToolCall(val connectionId: String, val name: String, val arguments: Map<String, String> = emptyMap())

sealed interface McpCallResult {
    data class Success(val payload: Any?) : McpCallResult
    data class Failure(val error: ToolError, val message: String? = null) : McpCallResult
    data class NetworkError(val detail: String? = null) : McpCallResult
}

data class McpToolResult(
    val success: Boolean,
    val payload: Any? = null,
    val error: ToolError? = null,
    val message: String? = null,
) {
    companion object {
        fun from(result: McpCallResult): McpToolResult = when (result) {
            is McpCallResult.Success -> McpToolResult(true, payload = result.payload)
            is McpCallResult.Failure -> McpToolResult(false, error = result.error, message = safeMessage(result.message))
            is McpCallResult.NetworkError -> McpToolResult(false, error = ToolError.NETWORK_ERROR, message = "MCP network request failed")
        }

        private fun safeMessage(message: String?): String? = message
            ?.replace(Regex("(?i)(token|authorization|password|secret|client_secret)\\s*[=:]\\s*[^,; ]+"), "$1=[redacted]")
            ?.take(256)
    }
}
