package com.fsaint.androidagent.mcp

import kotlinx.serialization.json.*
import java.net.URI

data class McpExchangeRequest(val url: String, val headers: Map<String, String>, val body: JsonObject, val timeoutMillis: Long = 10_000)
data class McpStreamResponse(val status: Int, val headers: Map<String, String> = emptyMap())
interface McpStreamTransport {
    /** Deliver complete JSON-RPC messages until the consumer returns true. Bound the raw stream. */
    suspend fun exchange(request: McpExchangeRequest, onMessage: suspend (JsonObject) -> Boolean): McpStreamResponse
}

class McpFailure(val kind: String, val detail: String) : Exception(detail)
fun validateMcpEndpoint(endpoint: String): String {
    val uri = try { URI(endpoint.trim()) } catch (_: java.net.URISyntaxException) { throw IllegalArgumentException("Use a valid HTTPS MCP endpoint.") }
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null && uri.port in -1..65535) { "Use an HTTPS MCP endpoint without embedded credentials or a fragment." }
    return uri.toASCIIString()
}

/** Bound nesting before invoking the JSON parser, including untrusted SSE payloads. */
fun parseMcpObject(text: String): JsonObject {
    if (text.toByteArray().size > 1_048_576) throw McpFailure("UNSUPPORTED", "MCP response exceeds the size limit.")
    var depth = 0; var quoted = false; var escaped = false
    for (char in text) {
        if (quoted) { if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false }
        else when (char) { '"' -> quoted = true; '{', '[' -> { depth++; if (depth > 64) throw McpFailure("UNSUPPORTED", "MCP JSON nesting exceeds the limit.") }; '}', ']' -> depth-- }
    }
    return try { Json.parseToJsonElement(text).jsonObject } catch (_: Exception) { throw McpFailure("UNSUPPORTED", "Malformed MCP JSON response.") }
}
