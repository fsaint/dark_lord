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
        val body = requestBody(event, context)
        return parsePlan(request(body))
    }

    override suspend fun respond(request: ConversationRequest): ConversationResponse {
        val body = requestBody(request.event, request.context, request.userText, request.transcript)
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
            throw OpenAiProviderException(error, "HTTP ${response.status}")
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

    private fun requestBody(event: AgentEvent, context: AgentContext, userText: String = event.payload["body"].orEmpty(), transcript: ConversationTranscript? = null): String {
        val history = transcript?.turns.orEmpty().joinToString("\\n") { it.toString() }
        val tools = context.resources.joinToString(",") {
            val parameters = if (it == "browser.open")
                "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\",\"description\":\"HTTPS URL to open\"}},\"required\":[\"url\"],\"additionalProperties\":false}"
            else if (it == "telegram.send_photo")
                "{\"type\":\"object\",\"properties\":{\"artifactId\":{\"type\":\"string\"},\"chatId\":{\"type\":\"string\"}},\"required\":[\"artifactId\"],\"additionalProperties\":false}"
            else "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"
            "{\"type\":\"function\",\"name\":\"${toolName(it)}\",\"description\":\"Phone capability: ${escape(it)}\",\"parameters\":$parameters}"
        }
        val inventory = buildString {
            append("Available phone tools: ").append(context.resources.sorted().joinToString(", "))
            if (context.mcpResources.isNotEmpty()) append(". Available MCP servers: ").append(context.mcpResources.sorted().joinToString(", "))
            if (context.skillResources.isNotEmpty()) append(". Available skills: ").append(context.skillResources.sorted().joinToString(", "))
            append('.')
        }
        return "{\"model\":\"gpt-4o-mini\",\"input\":\"${escape(inventory)}\\n${escape(userText)}\\n${escape(history)}\",\"tools\":[$tools]}"
    }

    private fun field(json: String, name: String): String? = Regex("\\\"$name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(json)?.groupValues?.get(1)
    private fun arguments(json: String): Map<String, String> {
        val body = Regex("\\\"arguments\\\"\\s*:\\s*\\{([^}]*)}").find(json)?.groupValues?.get(1) ?: return emptyMap()
        return Regex("\\\"([^\\\"]+)\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").findAll(body)
            .associate { it.groupValues[1] to it.groupValues[2].replace("\\\\\"", "\"").replace("\\\\\\\\", "\\\\") }
    }
    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"").take(16_384)
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
