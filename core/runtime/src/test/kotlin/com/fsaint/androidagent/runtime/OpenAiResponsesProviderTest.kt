package com.fsaint.androidagent.runtime

import com.fsaint.androidagent.model.AgentEvent
import com.fsaint.androidagent.model.PrincipalRole
import com.fsaint.androidagent.policy.AgentContext
import com.fsaint.androidagent.policy.Principal
import com.fsaint.androidagent.policy.ScopeRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenAiResponsesProviderTest {
    private val session = ScopeRegistry().sessionFor(Principal("owner", null, PrincipalRole.OWNER), "local")
    private val event = AgentEvent("e1", "request", "local", 1, mapOf("body" to "check battery"))
    private val context = AgentContext(setOf("device.battery"), emptyMap())

    @Test
    fun postsScopedContextAndParsesToolCall() = runTest {
        val transport = FakeTransport { request ->
            assertEquals("https://api.openai.com/v1/responses", request.url)
            assertTrue(request.authorization.startsWith("Bearer "))
            assertTrue(request.body.contains("device.battery"))
            OpenAiHttpResponse(200, "{\"tool\":\"device.battery\",\"arguments\":{}}")
        }
        val provider = OpenAiResponsesProvider(OpenAiHttpClient(transport, StaticApiKey("sk-test")))

        assertEquals(PlannedAction.Tool(com.fsaint.androidagent.model.ToolCall("device.battery")), provider.plan(session, event, context))
    }

    @Test
    fun includesInventoryAndMapsModelToolNamesBackToCanonicalIds() = runTest {
        val transport = FakeTransport { request ->
            assertTrue(request.body.contains("Available phone tools: device.battery"))
            assertTrue(request.body.contains("Use phone tools for real-world actions"))
            assertTrue(request.body.contains("Use artifacts as the handoff format"))
            OpenAiHttpResponse(200, "{\"tool\":\"device_battery\"}")
        }
        val client = OpenAiHttpClient(transport, StaticApiKey("sk-test"))
        val response = client.respond(ConversationRequest(
            session = session,
            event = event,
            context = AgentContext(setOf("device.battery"), emptyMap()),
            userText = "What tools do you have?",
        ))
        assertEquals(ConversationResponse.Tool(com.fsaint.androidagent.model.ToolCall("device.battery")), response)
    }

    @Test
    fun forwardsBrowserUrlArgumentsAndAdvertisesUrlSchema() = runTest {
        val transport = FakeTransport { request ->
            assertTrue(request.body.contains("\"name\":\"browser_open\""))
            assertTrue(request.body.contains("\"url\":{\"type\":\"string\""))
            OpenAiHttpResponse(200, "{\"tool\":\"browser_open\",\"arguments\":{\"url\":\"https://example.com\"}}")
        }
        val client = OpenAiHttpClient(transport, StaticApiKey("sk-test"))
        val response = client.respond(ConversationRequest(session, event, AgentContext(setOf("browser.open"), emptyMap()), "open example"))

        assertEquals(
            ConversationResponse.Tool(com.fsaint.androidagent.model.ToolCall("browser.open", mapOf("url" to "https://example.com"))),
            response,
        )
    }

    @Test
    fun parsesToolArgumentsOnAndroidCompatibleRegex() = runTest {
        val client = OpenAiHttpClient(FakeTransport {
            OpenAiHttpResponse(200, "{\"tool\":\"telegram_send_photo\",\"arguments\":{\"artifactId\":\"artifact_123\"}}")
        }, StaticApiKey("sk-test"))

        val response = client.respond(ConversationRequest(session, event, AgentContext(setOf("telegram.send_photo"), emptyMap()), "send it"))

        assertEquals(
            ConversationResponse.Tool(com.fsaint.androidagent.model.ToolCall("telegram.send_photo", mapOf("artifactId" to "artifact_123"))),
            response,
        )
    }

    @Test
    fun parsesResponsesApiFunctionCallArgumentsString() = runTest {
        val client = OpenAiHttpClient(FakeTransport {
            OpenAiHttpResponse(200, "{\"output\":[{\"type\":\"function_call\",\"arguments\":\"{\\\"code\\\":\\\"137 * 29\\\",\\\"arguments\\\":\\\"\\\"}\",\"name\":\"python_exec\"}]}")
        }, StaticApiKey("sk-test"))

        val response = client.respond(ConversationRequest(session, event, AgentContext(setOf("python.exec"), emptyMap()), "calculate"))

        assertEquals(
            ConversationResponse.Tool(com.fsaint.androidagent.model.ToolCall("python.exec", mapOf("code" to "137 * 29", "arguments" to ""))),
            response,
        )
    }

    @Test
    fun tellsModelWhichChatItIsInSoItNeverAsksForAChatId() = runTest {
        val telegram = ScopeRegistry().sessionFor(Principal("owner", null, PrincipalRole.OWNER), "TELEGRAM")
        val message = AgentEvent("telegram:7", "telegram.received", "123456789", 1, mapOf("sender" to "123456789", "body" to "send me a photo"))
        var body = ""
        val client = OpenAiHttpClient(FakeTransport { request ->
            body = request.body
            OpenAiHttpResponse(200, "{\"output_text\":\"ok\"}")
        }, StaticApiKey("sk-test"))

        client.respond(ConversationRequest(telegram, message, AgentContext(setOf("telegram.send_photo"), emptyMap()), "send me a photo"))

        assertTrue(body.contains("Conversation channel: TELEGRAM"), body)
        assertTrue(body.contains("Chat id: 123456789"), body)
        assertTrue(body.contains("never ask the user for it"), body)
        assertTrue(body.contains("\"chatId\":{\"type\":\"string\",\"description\":\"Optional. The app supplies the current authenticated Telegram chat automatically. Never ask the owner for a chat ID.\"}"), body)
    }

    @Test
    fun serializesToolResultsForTheNextConversationTurn() = runTest {
        var body = ""
        val client = OpenAiHttpClient(FakeTransport { request ->
            body = request.body
            OpenAiHttpResponse(200, "{\"output_text\":\"done\"}")
        }, StaticApiKey("sk-test"))

        client.respond(ConversationRequest(
            session,
            event,
            context,
            "check battery",
            ConversationTranscript(listOf(
                ConversationTurn.AssistantTool(com.fsaint.androidagent.model.ToolCall("device.battery")),
                ConversationTurn.ToolOutput(
                    com.fsaint.androidagent.model.ToolCall("device.battery"),
                    com.fsaint.androidagent.model.ToolResult(true, "100%"),
                ),
            ), nextTurn = 1),
        ))

        assertTrue(body.contains("Tool result for device.battery"), body)
        assertTrue(body.contains("100%"), body)
    }

    @Test
    fun omitsChatIdLineWhenTheEventHasNoSender() = runTest {
        var body = ""
        val client = OpenAiHttpClient(FakeTransport { request ->
            body = request.body
            OpenAiHttpResponse(200, "{\"output_text\":\"ok\"}")
        }, StaticApiKey("sk-test"))

        client.respond(ConversationRequest(session, AgentEvent("voice:1", "voice.transcript", "voice", 1, mapOf("body" to "hi")), context, "hi"))

        assertTrue(body.contains("Conversation channel: local"), body)
        assertTrue(!body.contains("Chat id:"), body)
    }

    @Test
    fun rejectsInsecureOrOversizedResponsesWithoutLeakingKey() = runTest {
        val provider = OpenAiResponsesProvider(OpenAiHttpClient(FakeTransport { OpenAiHttpResponse(200, "x".repeat(100)) }, StaticApiKey("sk-secret"), maxBodyBytes = 32))
        val result = runCatching { provider.plan(session, event, context) }
        assertTrue(result.exceptionOrNull() is OpenAiProviderException)
        assertTrue(result.exceptionOrNull()?.message?.contains("sk-secret") != true)
    }

    @Test
    fun stripsWhitespaceBeforeSendingAuthorizationHeader() = runTest {
        var authorization = ""
        val provider = OpenAiResponsesProvider(OpenAiHttpClient(FakeTransport {
            authorization = it.authorization
            OpenAiHttpResponse(200, "{\"tool\":\"device.battery\"}")
        }, StaticApiKey("  sk-valid\r\nmalformed \t")))

        provider.plan(session, event, context)
        assertEquals("Bearer sk-validmalformed", authorization)
    }

    private class FakeTransport(private val responder: (OpenAiHttpRequest) -> OpenAiHttpResponse) : OpenAiHttpTransport {
        override suspend fun execute(request: OpenAiHttpRequest): OpenAiHttpResponse = responder(request)
    }
    private class StaticApiKey(private val value: String) : OpenAiApiKeyProvider {
        override suspend fun apiKey(): String = value
    }
}
