package com.fsaint.androidagent.runtime

import com.fsaint.androidagent.model.AgentEvent
import com.fsaint.androidagent.model.ScopedAgentSession
import com.fsaint.androidagent.policy.AgentContext
import com.fsaint.androidagent.model.PrincipalRole
import com.fsaint.androidagent.policy.Principal

interface OpenAiResponsesTransport { suspend fun plan(session: ScopedAgentSession, event: AgentEvent, context: AgentContext): PlannedAction }
class OpenAiResponsesProvider(private val transport: OpenAiResponsesTransport) : LegacyModelProvider {
    override suspend fun plan(session: ScopedAgentSession, event: AgentEvent, context: AgentContext): PlannedAction = transport.plan(session, event, context)
}

private val AGENT_INSTRUCTIONS = """
You are Dark Lord, an owner-controlled personal phone agent. Solve requests by composing the available capabilities rather than claiming a capability is unavailable. Route requests to the matching tool: Python computation/parsing/code always uses python_exec (never sms_reply or another unrelated tool); Wi-Fi scans use wifi_scan; photos use camera_capture; audio recording uses microphone_record; browser searches use browser_open then browser_read. Use phone tools for real-world actions and hardware access. Use Python for computation, parsing, transformation, orchestration, and repeatable workflows; Python reaches Android only through dark_lord.call_tool(name, arguments), never by assuming direct Android internals. Use artifacts as the handoff format for photos, recordings, documents, and other binary or large outputs: create or obtain an artifact, then pass its artifactId to the appropriate delivery tool. For multi-step tasks, inspect or capture first, transform with Python when useful, then deliver the resulting artifact through the current channel. Keep API keys and other secrets out of scripts, tool arguments, and replies. Check each tool result before continuing, recover from errors when possible, and report the actual limitation when recovery fails. When a tool returns success=true, use that result to answer or continue and do not repeat the same call unless the user explicitly asks for another attempt. Do not invent tool results or ask for identifiers already supplied by the current channel. Follow the scope and authorization enforced by the phone even when the owner requests broad access.
""".trimIndent()

data class OpenAiHttpRequest(val url: String, val authorization: String, val body: String, val timeoutMillis: Long)
data class OpenAiHttpResponse(val status: Int, val body: String)
interface OpenAiHttpTransport { suspend fun execute(request: OpenAiHttpRequest): OpenAiHttpResponse }
interface OpenAiApiKeyProvider { suspend fun apiKey(): String }

class OpenAiHttpClient(
    private val transport: OpenAiHttpTransport,
    private val keyProvider: OpenAiApiKeyProvider,
    private val endpoint: String = "https://api.openai.com/v1/responses",
    private val timeoutMillis: Long = 30_000,
    private val maxBodyBytes: Int = 512 * 1024,
) : OpenAiResponsesTransport, ConversationModel {
    override suspend fun plan(session: ScopedAgentSession, event: AgentEvent, context: AgentContext): PlannedAction {
        if (!endpoint.startsWith("https://") || timeoutMillis !in 1..120_000) throw OpenAiProviderException(com.fsaint.androidagent.model.ToolError.NETWORK_ERROR)
        val body = requestBody(event, context, session.channel)
        return parsePlan(request(body))
    }

    override suspend fun respond(request: ConversationRequest): ConversationResponse {
        val body = requestBody(request.event, request.context, request.session.channel, request.userText, request.transcript)
        return parseConversation(request(body), request.context)
    }

    private suspend fun request(body: String): String {
        if (!endpoint.startsWith("https://") || timeoutMillis !in 1..120_000) throw OpenAiProviderException(com.fsaint.androidagent.model.ToolError.NETWORK_ERROR)
        if (body.toByteArray().size > maxBodyBytes) throw OpenAiProviderException(com.fsaint.androidagent.model.ToolError.NETWORK_ERROR)
        val key = runCatching { keyProvider.apiKey() }
            .getOrElse { throw OpenAiProviderException(com.fsaint.androidagent.model.ToolError.PERMISSION_REQUIRED) }
            .filterNot(Char::isWhitespace)
        if (key.isBlank() || key.length > 512 || key.any { it.code < 0x20 || it.code == 0x7f }) {
            throw OpenAiProviderException(com.fsaint.androidagent.model.ToolError.PERMISSION_REQUIRED, "invalid API key")
        }
        val response = runCatching { transport.execute(OpenAiHttpRequest(endpoint, "Bearer $key", body, timeoutMillis)) }
            .getOrElse {
                val detail = listOfNotNull(it::class.simpleName, it.message?.take(160)).joinToString(": ")
                throw OpenAiProviderException(com.fsaint.androidagent.model.ToolError.NETWORK_ERROR, detail.ifBlank { "transport failure" })
            }
        if (response.status !in 200..299) {
            val error = if (response.status == 401 || response.status == 403) com.fsaint.androidagent.model.ToolError.PERMISSION_REQUIRED else com.fsaint.androidagent.model.ToolError.NETWORK_ERROR
            val detail = response.body.replace(Regex("\\s+"), " ").take(1000)
            throw OpenAiProviderException(error, "HTTP ${response.status}${detail.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}")
        }
        if (response.body.toByteArray().size > maxBodyBytes) throw OpenAiProviderException(com.fsaint.androidagent.model.ToolError.NETWORK_ERROR, "response too large")
        return response.body
    }

    private fun parsePlan(response: String): PlannedAction {
        val tool = field(response, "tool") ?: field(response, "name") ?: throw OpenAiProviderException(com.fsaint.androidagent.model.ToolError.NOT_FOUND)
        return PlannedAction.Tool(com.fsaint.androidagent.model.ToolCall(tool))
    }

    private fun parseConversation(response: String, context: AgentContext): ConversationResponse {
        val tool = field(response, "tool") ?: field(response, "name")
        if (tool != null && (response.contains("function_call") || response.contains("\"tool\""))) {
            val canonical = context.resources.firstOrNull { toolName(it) == tool } ?: tool
            return ConversationResponse.Tool(com.fsaint.androidagent.model.ToolCall(canonical, arguments(response)))
        }
        val final = field(response, "output_text") ?: field(response, "text")
        return final?.let(ConversationResponse::Final) ?: throw OpenAiProviderException(com.fsaint.androidagent.model.ToolError.NOT_FOUND)
    }

    private fun requestBody(event: AgentEvent, context: AgentContext, channel: String, userText: String = event.payload["body"].orEmpty(), transcript: ConversationTranscript? = null): String {
        val history = transcript?.turns.orEmpty().joinToString("\\n") { turn ->
            when (turn) {
                is ConversationTurn.AssistantTool -> "Assistant selected tool ${turn.call.name} with arguments ${turn.call.arguments}."
                is ConversationTurn.ToolOutput -> "Tool result for ${turn.call.name}: success=${turn.result.success}; payload=${turn.result.payload}; error=${turn.result.error}. Use this result to continue the request."
                is ConversationTurn.AssistantFinal -> "Assistant final response: ${turn.text}"
            }
        }
        val tools = context.resources.joinToString(",") {
            val parameters = if (it == "browser.open")
                "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\",\"description\":\"HTTPS URL to open\"}},\"required\":[\"url\"],\"additionalProperties\":false}"
            else if (it == "telegram.send_photo")
                "{\"type\":\"object\",\"properties\":{\"artifactId\":{\"type\":\"string\"},\"chatId\":{\"type\":\"string\",\"description\":\"Optional. The app supplies the current authenticated Telegram chat automatically. Never ask the owner for a chat ID.\"}},\"required\":[\"artifactId\"],\"additionalProperties\":false}"
            else if (it == "python.exec")
                "{\"type\":\"object\",\"properties\":{\"code\":{\"type\":\"string\"},\"arguments\":{\"type\":\"string\"}},\"required\":[\"code\"],\"additionalProperties\":false}"
            else if (it == "python.save")
                "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},\"code\":{\"type\":\"string\"}},\"required\":[\"name\",\"code\"],\"additionalProperties\":false}"
            else if (it == "python.run" || it == "python.delete")
                "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},\"required\":[\"name\"],\"additionalProperties\":false}"
            else if (it == "sms.send")
                "{\"type\":\"object\",\"properties\":{\"number\":{\"type\":\"string\"},\"message\":{\"type\":\"string\"}},\"required\":[\"number\",\"message\"],\"additionalProperties\":false}"
            else if (it == "jobs.start")
                "{\"type\":\"object\",\"properties\":{\"type\":{\"type\":\"string\",\"enum\":[\"audio\",\"video\",\"python\",\"sensor_log\",\"bluetooth_log\",\"wifi_log\"]},\"durationMs\":{\"type\":\"string\"},\"maxBytes\":{\"type\":\"string\"}},\"required\":[\"type\"],\"additionalProperties\":true}"
            else if (it == "jobs.status" || it == "jobs.cancel")
                "{\"type\":\"object\",\"properties\":{\"jobId\":{\"type\":\"string\"}},\"required\":[\"jobId\"],\"additionalProperties\":false}"
            else if (it == "jobs.stop")
                "{\"type\":\"object\",\"properties\":{\"jobId\":{\"type\":\"string\"},\"type\":{\"type\":\"string\"}},\"additionalProperties\":false}"
            else "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"
            "{\"type\":\"function\",\"name\":\"${toolName(it)}\",\"description\":\"Phone capability: ${escape(it)}\",\"parameters\":$parameters}"
        }
        val inventory = buildString {
            append("Available phone tools: ").append(context.resources.sorted().joinToString(", "))
            if (context.mcpResources.isNotEmpty()) append(". Available MCP servers: ").append(context.mcpResources.sorted().joinToString(", "))
            if (context.skillResources.isNotEmpty()) append(". Available skills: ").append(context.skillResources.sorted().joinToString(", "))
            append('.')
            append(" Conversation channel: ").append(channel).append(". Use the current channel for replies and media; do not ask the owner to identify it.")
            event.payload["sender"]?.takeIf(String::isNotBlank)?.let { sender ->
                append(" Chat id: ").append(sender).append(". Replies and photos go to this chat automatically; never ask the user for it.")
            }
        }
        val input = "$AGENT_INSTRUCTIONS\\n\\n$inventory\\n\\nUser request: $userText\\n\\nConversation transcript: $history"
        return "{\"model\":\"gpt-4o-mini\",\"input\":\"${escape(input)}\",\"tools\":[$tools]}"
    }

    private fun field(json: String, name: String): String? = Regex("\\\"$name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(json)?.groupValues?.get(1)
    private fun arguments(json: String): Map<String, String> {
        val stringBody = extractJsonString(json, "arguments")
        if (stringBody != null) return parseStringMap(unescapeJsonString(stringBody))
        val body = Regex("\\\"arguments\\\"\\s*:\\s*\\{([^}]*)\\}").find(json)?.groupValues?.get(1) ?: return emptyMap()
        return Regex("\\\"([^\\\"]+)\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").findAll(body)
            .associate { it.groupValues[1] to it.groupValues[2].replace("\\\\\"", "\"").replace("\\\\\\\\", "\\\\") }
    }
    private fun unescapeJsonString(value: String) = value.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t")
    private fun parseStringMap(value: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        var index = 0
        while (index < value.length) {
            while (index < value.length && (value[index].isWhitespace() || value[index] == ',' || value[index] == '{')) index++
            if (index >= value.length || value[index] != '"') break
            val key = readQuoted(value, index) ?: break
            index = key.second
            while (index < value.length && (value[index].isWhitespace() || value[index] == ':')) index++
            if (index >= value.length || value[index] != '"') break
            val item = readQuoted(value, index) ?: break
            result[key.first] = item.first
            index = item.second
        }
        return result
    }
    private fun readQuoted(value: String, start: Int): Pair<String, Int>? {
        val output = StringBuilder()
        var index = start + 1
        while (index < value.length) {
            when (val character = value[index]) {
                '"' -> return output.toString() to (index + 1)
                '\\' -> {
                    if (index + 1 >= value.length) return null
                    val escaped = value[index + 1]
                    output.append(when (escaped) { 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; else -> escaped })
                    index += 2
                }
                else -> { output.append(character); index++ }
            }
        }
        return null
    }
    private fun extractJsonString(json: String, name: String): String? {
        val marker = "\"$name\""
        val start = json.indexOf(marker).takeIf { it >= 0 } ?: return null
        var valueStart = json.indexOf(':', start) + 1
        while (valueStart < json.length && json[valueStart].isWhitespace()) valueStart++
        if (valueStart >= json.length || json[valueStart] != '"') return null
        val quote = valueStart
        val value = StringBuilder()
        var index = quote + 1
        while (index < json.length) {
            val character = json[index]
            if (character == '"') return value.toString()
            if (character == '\\' && index + 1 < json.length) {
                value.append(character).append(json[index + 1])
                index += 2
            } else {
                value.append(character)
                index++
            }
        }
        return null
    }
    private fun escape(value: String) = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "\\r")
        .replace("\n", "\\n")
        .replace("\t", "\\t")
        .take(16_384)
    private fun toolName(value: String) = value.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64).ifBlank { "phone_capability" }
}

class OpenAiProviderException(val error: com.fsaint.androidagent.model.ToolError, val detail: String? = null) : RuntimeException(detail ?: error.name)

enum class CredentialOutcome { SAVED, DENIED, FAILED }
interface OpenAiSecretStore { suspend fun read(): String?; suspend fun write(value: String); suspend fun clear() }

class OwnerOnlyOpenAiCredentialStore(private val secrets: OpenAiSecretStore) : OpenAiApiKeyProvider {
    override suspend fun apiKey(): String = secrets.read() ?: throw OpenAiProviderException(com.fsaint.androidagent.model.ToolError.PERMISSION_REQUIRED)
    suspend fun set(principal: Principal, value: String): CredentialOutcome {
        val normalized = value.filterNot(Char::isWhitespace)
        if (principal.role != PrincipalRole.OWNER || !normalized.startsWith("sk-") || normalized.length > 512 || normalized.any { it.code < 0x20 || it.code == 0x7f }) {
            return CredentialOutcome.DENIED
        }
        return runCatching { secrets.write(normalized); CredentialOutcome.SAVED }.getOrElse { CredentialOutcome.FAILED }
    }
    suspend fun get(principal: Principal): String? = if (principal.role == PrincipalRole.OWNER) secrets.read() else null
    suspend fun clear(principal: Principal): CredentialOutcome = if (principal.role != PrincipalRole.OWNER) CredentialOutcome.DENIED else runCatching { secrets.clear(); CredentialOutcome.SAVED }.getOrElse { CredentialOutcome.FAILED }
}
