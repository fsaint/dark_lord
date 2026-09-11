package com.fsaint.androidagent.mcp

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicLong

data class LiveMcpTool(val name: String, val description: String, val inputSchema: JsonObject)
data class McpSession(val endpoint: String, val version: String, val id: String?, var toolsChanged: Boolean = false)
data class McpDiscovery(val session: McpSession, val tools: List<LiveMcpTool>, val notices: List<String>)

class LiveMcpClient(private val transport: McpStreamTransport) {
    private val ids = AtomicLong()
    suspend fun discover(endpoint: String): McpDiscovery {
        val url = validateMcpEndpoint(endpoint)
        val (init, response) = rpc(url, null, "initialize", buildJsonObject {
            put("protocolVersion", "2025-11-25")
            put("capabilities", buildJsonObject {})
            put("clientInfo", buildJsonObject { put("name", "Dark Lord"); put("version", "0.1.0") })
        })
        val version = init["protocolVersion"]?.jsonPrimitive?.content
        if (version !in setOf("2025-11-25", "2025-06-18", "2025-03-26")) throw McpFailure("UNSUPPORTED", "Unsupported MCP protocol version.")
        val sessionId = response.headers.entries.firstOrNull { it.key.equals("MCP-Session-Id", true) }?.value
        if (sessionId != null && (sessionId.length !in 1..1024 || sessionId.any { it.code !in 0x21..0x7e })) throw McpFailure("UNSUPPORTED", "Invalid MCP session header.")
        val session = McpSession(url, requireNotNull(version), sessionId)
        notify(session, "notifications/initialized")
        if ((init["capabilities"] as? JsonObject)?.containsKey("tools") != true) return McpDiscovery(session, emptyList(), listOf("Server does not advertise tool support."))
        val tools = mutableListOf<LiveMcpTool>(); val notices = mutableListOf<String>(); val cursors = mutableSetOf<String>()
        var cursor: String? = null
        repeat(10) {
            val (result, _) = rpc(url, session, "tools/list", buildJsonObject { cursor?.let { put("cursor", it) } })
            val list = result["tools"] as? JsonArray ?: throw McpFailure("UNSUPPORTED", "Invalid MCP tool list.")
            for (entry in list) {
                if (tools.size == 64) { notices += "Tool catalog limited to 64 tools for this server."; return McpDiscovery(session, tools, notices) }
                val obj = entry as? JsonObject ?: throw McpFailure("UNSUPPORTED", "Invalid MCP tool descriptor.")
                val name = (obj["name"] as? JsonPrimitive)?.contentOrNull
                val schema = obj["inputSchema"] as? JsonObject
                if (name.isNullOrBlank() || name.length > 128 || schema == null || schema.toString().toByteArray().size > 32768 || !supportedSchema(schema) || obj["execution"]?.jsonObject?.get("taskSupport")?.jsonPrimitive?.contentOrNull == "required") {
                    notices += "A tool was omitted because its schema or execution mode is unsupported."
                    continue
                }
                if (tools.any { it.name == name }) throw McpFailure("UNSUPPORTED", "Server returned duplicate tool names.")
                tools += LiveMcpTool(name, (obj["description"] as? JsonPrimitive)?.contentOrNull.orEmpty().take(2048), schema)
            }
            cursor = (result["nextCursor"] as? JsonPrimitive)?.contentOrNull
            if (cursor == null) return McpDiscovery(session, tools, notices.distinct())
            if (!cursors.add(requireNotNull(cursor))) throw McpFailure("UNSUPPORTED", "MCP pagination repeated a cursor.")
        }
        return McpDiscovery(session, tools, notices + "Tool discovery limited to 10 pages.")
    }

    suspend fun call(session: McpSession, name: String, arguments: JsonObject): JsonObject = rpc(session.endpoint, session, "tools/call", buildJsonObject {
        put("name", name); put("arguments", arguments)
    }, 60_000).first

    private fun headers(session: McpSession?) = buildMap {
        put("Content-Type", "application/json"); put("Accept", "application/json, text/event-stream")
        put("User-Agent", "DarkLord/0.1 (Android; MCP client)")
        session?.let { put("MCP-Protocol-Version", it.version); it.id?.let { id -> put("MCP-Session-Id", id) } }
    }
    private suspend fun notify(session: McpSession, method: String) {
        val response = withTimeoutOrNull(10_000) { transport.exchange(McpExchangeRequest(session.endpoint, headers(session), buildJsonObject { put("jsonrpc", "2.0"); put("method", method) })) { false } }
            ?: throw McpFailure("UNREACHABLE", "MCP request timed out; outcome unknown.")
        checkStatus(response.status, session)
    }
    private suspend fun rpc(url: String, session: McpSession?, method: String, params: JsonObject, timeout: Long = 10_000): Pair<JsonObject, McpStreamResponse> = withTimeoutOrNull(timeout) {
        val id = JsonPrimitive(ids.incrementAndGet())
        var answer: JsonObject? = null
        val request = McpExchangeRequest(url, headers(session), buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) }, timeout)
        val response = transport.exchange(request) { message ->
            if (message["jsonrpc"]?.jsonPrimitive?.content != "2.0") throw McpFailure("UNSUPPORTED", "Invalid JSON-RPC version.")
            if (message["method"] != null) {
                if (message["method"]?.jsonPrimitive?.content == "notifications/tools/list_changed") session?.toolsChanged = true
                if (message["id"] != null) {
                    val rejection = buildJsonObject { put("jsonrpc", "2.0"); put("id", message["id"]!!); put("error", buildJsonObject { put("code", -32601); put("message", "Client capability not supported") }) }
                    val rejected = transport.exchange(McpExchangeRequest(url, headers(session), rejection, timeout)) { false }
                    checkStatus(rejected.status, session)
                }
                false
            } else if (message["id"] == id) { answer = message; true } else false
        }
        checkStatus(response.status, session)
        val envelope = answer ?: throw McpFailure("UNREACHABLE", "MCP stream ended without a matching response; outcome unknown.")
        if (envelope["error"] != null) throw McpFailure("PROTOCOL_ERROR", "MCP server returned a protocol error.")
        (envelope["result"] as? JsonObject ?: throw McpFailure("UNSUPPORTED", "Invalid MCP result.")) to response
    } ?: throw McpFailure("UNREACHABLE", "MCP request timed out; outcome unknown.")
    private fun checkStatus(status: Int, session: McpSession?) {
        if (status in 200..299) return
        throw when {
            status == 401 || status == 403 -> McpFailure("AUTH_REQUIRED", "Authentication required; OAuth is not connected yet.")
            status == 404 && session?.id != null -> McpFailure("SESSION_EXPIRED", "MCP session expired; reconnect before the next request.")
            else -> McpFailure("UNREACHABLE", "MCP HTTP request failed ($status).")
        }
    }

    private fun supportedSchema(schema: JsonObject): Boolean {
        if (schema["type"] != JsonPrimitive("object")) return false
        val types = setOf("object", "array", "string", "number", "integer", "boolean", "null")
        fun string(value: JsonElement) = value is JsonPrimitive && value.isString
        fun safe(node: JsonElement): Boolean {
            if (node is JsonPrimitive) return !node.isString && node.booleanOrNull != null
            if (node !is JsonObject) return false
            return node.all { (key, value) -> when (key) {
                "\$ref", "\$dynamicRef", "\$recursiveRef" -> false
                "type" -> if (value is JsonArray) value.isNotEmpty() && value.distinct().size == value.size && value.all { string(it) && it.jsonPrimitive.content in types } else string(value) && value.jsonPrimitive.content in types
                "properties", "\$defs", "definitions", "dependentSchemas" -> value is JsonObject && value.values.all(::safe)
                "items", "additionalProperties", "unevaluatedProperties", "unevaluatedItems", "contains", "not", "if", "then", "else", "propertyNames" -> safe(value)
                "allOf", "anyOf", "oneOf", "prefixItems" -> value is JsonArray && value.isNotEmpty() && value.all(::safe)
                "required" -> value is JsonArray && value.all(::string) && value.distinct().size == value.size
                "dependentRequired" -> value is JsonObject && value.values.all { it is JsonArray && it.all(::string) && it.distinct().size == it.size }
                "enum" -> value is JsonArray && value.isNotEmpty() && value.distinct().size == value.size
                "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf" -> value is JsonPrimitive && !value.isString && value.doubleOrNull?.let { it.isFinite() && (key != "multipleOf" || it > 0) } == true
                "minLength", "maxLength", "minItems", "maxItems", "minProperties", "maxProperties", "minContains", "maxContains" -> value is JsonPrimitive && !value.isString && value.longOrNull?.let { it >= 0 } == true
                "uniqueItems", "readOnly", "writeOnly", "deprecated" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
                "title", "description", "format", "\$schema", "\$id", "\$comment", "contentEncoding", "contentMediaType" -> string(value)
                // Regex dialects differ between Android and model providers. Omit instead of changing semantics.
                "pattern", "patternProperties", "dependencies", "additionalItems", "\$vocabulary", "\$anchor", "\$dynamicAnchor", "contentSchema" -> false
                "examples" -> value is JsonArray
                "default", "const" -> true
                else -> false
            } }
        }
        return safe(schema)
    }
}
