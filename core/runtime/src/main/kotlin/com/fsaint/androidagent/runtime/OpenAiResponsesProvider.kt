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
        return parseConversation(request(body))
    }

    private suspend fun request(body: String): String {
        if (!endpoint.startsWith("https://") || timeoutMillis !in 1..120_000) throw OpenAiProviderException(com.fsaint.androidagent.model.ToolError.NETWORK_ERROR)
        if (body.toByteArray().size > maxBodyBytes) throw OpenAiProviderException(com.fsaint.androidagent.model.ToolError.NETWORK_ERROR)
        val key = runCatching { keyProvider.apiKey() }.getOrElse { throw OpenAiProviderException(com.fsaint.androidagent.model.ToolError.PERMISSION_REQUIRED) }
        if (key.isBlank() || key.length > 512) throw OpenAiProviderException(com.fsaint.androidagent.model.ToolError.PERMISSION_REQUIRED)
        val response = runCatching { transport.execute(OpenAiHttpRequest(endpoint, "Bearer $key", body, timeoutMillis)) }
            .getOrElse { throw OpenAiProviderException(com.fsaint.androidagent.model.ToolError.NETWORK_ERROR) }
        if (response.status !in 200..299 || response.body.toByteArray().size > maxBodyBytes) throw OpenAiProviderException(com.fsaint.androidagent.model.ToolError.NETWORK_ERROR)
        return response.body
    }

    private fun parsePlan(response: String): PlannedAction {
        val tool = field(response, "tool") ?: field(response, "name") ?: throw OpenAiProviderException(com.fsaint.androidagent.model.ToolError.NOT_FOUND)
        return PlannedAction.Tool(com.fsaint.androidagent.model.ToolCall(tool))
    }

    private fun parseConversation(response: String): ConversationResponse {
        val tool = field(response, "tool") ?: field(response, "name")
        if (tool != null && (response.contains("function_call") || response.contains("\"tool\""))) return ConversationResponse.Tool(com.fsaint.androidagent.model.ToolCall(tool))
        val final = field(response, "output_text") ?: field(response, "text")
        return final?.let(ConversationResponse::Final) ?: throw OpenAiProviderException(com.fsaint.androidagent.model.ToolError.NOT_FOUND)
    }

    private fun requestBody(event: AgentEvent, context: AgentContext, userText: String = event.payload["body"].orEmpty(), transcript: ConversationTranscript? = null): String {
        val history = transcript?.turns.orEmpty().joinToString("\\n") { it.toString() }
        val tools = context.resources.joinToString(",") { "{\"name\":\"${escape(it)}\"}" }
        return "{\"model\":\"gpt-4o-mini\",\"input\":\"${escape(userText)}\\n${escape(history)}\",\"tools\":[$tools]}"
    }

    private fun field(json: String, name: String): String? = Regex("\\\"$name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(json)?.groupValues?.get(1)
    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"").take(16_384)
}

class OpenAiProviderException(val error: com.fsaint.androidagent.model.ToolError) : RuntimeException(error.name)

enum class CredentialOutcome { SAVED, DENIED, FAILED }
interface OpenAiSecretStore { suspend fun read(): String?; suspend fun write(value: String); suspend fun clear() }

class OwnerOnlyOpenAiCredentialStore(private val secrets: OpenAiSecretStore) : OpenAiApiKeyProvider {
    override suspend fun apiKey(): String = secrets.read() ?: throw OpenAiProviderException(com.fsaint.androidagent.model.ToolError.PERMISSION_REQUIRED)
    suspend fun set(principal: Principal, value: String): CredentialOutcome {
        if (principal.role != PrincipalRole.OWNER || !value.trim().startsWith("sk-") || value.length > 512) return CredentialOutcome.DENIED
        return runCatching { secrets.write(value.trim()); CredentialOutcome.SAVED }.getOrElse { CredentialOutcome.FAILED }
    }
    suspend fun get(principal: Principal): String? = if (principal.role == PrincipalRole.OWNER) secrets.read() else null
    suspend fun clear(principal: Principal): CredentialOutcome = if (principal.role != PrincipalRole.OWNER) CredentialOutcome.DENIED else runCatching { secrets.clear(); CredentialOutcome.SAVED }.getOrElse { CredentialOutcome.FAILED }
}
