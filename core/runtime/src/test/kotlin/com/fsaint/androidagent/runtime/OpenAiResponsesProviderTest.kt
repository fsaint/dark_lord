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
    fun rejectsInsecureOrOversizedResponsesWithoutLeakingKey() = runTest {
        val provider = OpenAiResponsesProvider(OpenAiHttpClient(FakeTransport { OpenAiHttpResponse(200, "x".repeat(100)) }, StaticApiKey("sk-secret"), maxBodyBytes = 32))
        val result = runCatching { provider.plan(session, event, context) }
        assertTrue(result.exceptionOrNull() is OpenAiProviderException)
        assertTrue(result.exceptionOrNull()?.message?.contains("sk-secret") != true)
    }

    private class FakeTransport(private val responder: (OpenAiHttpRequest) -> OpenAiHttpResponse) : OpenAiHttpTransport {
        override suspend fun execute(request: OpenAiHttpRequest): OpenAiHttpResponse = responder(request)
    }
    private class StaticApiKey(private val value: String) : OpenAiApiKeyProvider {
        override suspend fun apiKey(): String = value
    }
}
