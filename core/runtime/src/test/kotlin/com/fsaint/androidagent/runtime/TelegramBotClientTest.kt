package com.fsaint.androidagent.runtime

import kotlinx.coroutines.test.runTest
import com.fsaint.androidagent.model.PrincipalRole
import com.fsaint.androidagent.policy.Principal
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TelegramBotClientTest {
    @Test
    fun acceptsTokensPastedWithWhitespaceAroundAndInsideToken() = runTest {
        val store = RecordingSecretStore()
        val credentials = OwnerOnlyTelegramBotCredentialStore(store)
        val outcome = credentials.set(Principal("owner", null, PrincipalRole.OWNER), "  123456:abcdefghij\r\nklmnopqrst  ")

        assertEquals(CredentialOutcome.SAVED, outcome)
        assertEquals("123456:abcdefghijklmnopqrst", store.value)
    }
    private val token = "123456789:ABCDEFGHIJKLMNOPQRSTUVWXYZabcd"

    @Test
    fun sendMessageBuildsHttpsUrlAndParsesSuccessWithoutLeakingTokenInRequestString() = runTest {
        val transport = FakeTransport(TelegramHttpResponse(200, "{\"ok\":true,\"result\":{\"message_id\":42}}"))
        val result = client(transport).sendMessage("-100123", "hello \"world\"")

        assertEquals(TelegramResult.Success(42), result)
        val request = transport.last!!
        assertEquals("https://api.telegram.org/bot$token/sendMessage", request.url)
        assertTrue(token !in request.toString())
        assertTrue(request.body.contains("hello \\\"world\\\""))
    }

    @Test
    fun parsesTextUpdatesAndRetainsNonTextUpdateIdsForAcknowledgement() = runTest {
        val transport = FakeTransport(TelegramHttpResponse(200, """
            {"ok":true,"result":[
              {"update_id":10,"message":{"chat":{"id":99},"text":"hello"}},
              {"update_id":11,"message":{"chat":{"id":99},"photo":[]}}
            ]}
        """.trimIndent()))

        assertEquals(listOf(TelegramUpdate(10, "99", "hello"), TelegramUpdate(11, "99")), client(transport).getUpdates(9, 12))
        val request = transport.last!!
        assertEquals("https://api.telegram.org/bot$token/getUpdates", request.url)
        assertTrue(request.body.contains("\"offset\":9"))
        assertTrue(request.body.contains("\"timeout\":12"))
    }

    @Test
    fun apiErrorsBecomeFailureAndGetUpdatesReturnsEmpty() = runTest {
        val transport = FakeTransport(TelegramHttpResponse(200, "{\"ok\":false,\"error_code\":401,\"description\":\"Unauthorized\"}"))
        val result = client(transport).sendMessage("1", "hello")
        assertEquals(TelegramResult.Failure(401, "Unauthorized"), result)
        assertTrue(client(transport).getUpdates(null, 1).isEmpty())
    }

    @Test
    fun payloadAndTimeoutAreBounded() = runTest {
        val transport = FakeTransport(TelegramHttpResponse(200, "{\"ok\":true,\"result\":[]}"))
        val bot = client(transport)
        assertIs<TelegramResult.Failure>(bot.sendMessage("1", "x".repeat(4_097)))
        bot.getUpdates(null, 500)
        val request = transport.last!!
        assertTrue(request.body.contains("\"timeout\":50"))
        assertEquals(80_000L, request.timeoutMillis)
    }

    @Test
    fun nonHttpsEndpointAndMissingTokenFailSafely() = runTest {
        val transport = FakeTransport(TelegramHttpResponse(200, "{\"ok\":true}"))
        assertIs<TelegramResult.Failure>(TelegramBotClient(transport, StaticToken(token), "http://localhost").sendMessage("1", "x"))
        assertIs<TelegramResult.Failure>(TelegramBotClient(transport, MissingToken()).sendMessage("1", "x"))
        assertEquals(0, transport.calls)
    }

    @Test
    fun onlyCanonicalTelegramOriginIsAccepted() = runTest {
        val transport = FakeTransport(TelegramHttpResponse(200, "{\"ok\":true}"))
        val rejectedOrigins = listOf(
            "https://evil.example",
            "https://api.telegram.org.evil.example",
            "https://user:pass@api.telegram.org",
            "https://api.telegram.org.evil.example/",
            "https://api.telegram.org/v1",
            "https://api.telegram.org/%2f",
            "https://api.telegram.org?redirect=evil",
            "https://api.telegram.org:8443",
        )

        rejectedOrigins.forEach { origin ->
            val result = TelegramBotClient(transport, StaticToken(token), origin).sendMessage("1", "x")
            assertIs<TelegramResult.Failure>(result, origin)
        }
        assertEquals(0, transport.calls)
    }

    @Test
    fun tokenMustBePathSafeBeforeTransportIsCalled() = runTest {
        val transport = FakeTransport(TelegramHttpResponse(200, "{\"ok\":true}"))
        val result = TelegramBotClient(transport, StaticToken("123:bad/token"))
            .sendMessage("1", "x")

        assertIs<TelegramResult.Failure>(result)
        assertEquals(0, transport.calls)
    }

    @Test
    fun redirectResponsesAreNeverAcceptedAsSuccess() = runTest {
        val transport = FakeTransport(TelegramHttpResponse(302, "{\"ok\":true,\"result\":{\"message_id\":1}}"))

        val result = client(transport).sendMessage("1", "x")

        assertEquals(TelegramResult.Failure(302, "Telegram request failed"), result)
    }

    private fun client(transport: FakeTransport) = TelegramBotClient(transport, StaticToken(token))

    private class StaticToken(private val value: String) : TelegramBotTokenProvider {
        override suspend fun apiToken(): String = value
    }

    private class MissingToken : TelegramBotTokenProvider {
        override suspend fun apiToken(): String = error("missing")
    }

    private class RecordingSecretStore : TelegramBotSecretStore {
        var value: String? = null
        override suspend fun read(): String? = value
        override suspend fun write(value: String) { this.value = value }
        override suspend fun clear() { value = null }
    }

    private class FakeTransport(private val response: TelegramHttpResponse) : TelegramHttpTransport {
        var last: TelegramHttpRequest? = null
        var calls = 0
        override suspend fun execute(request: TelegramHttpRequest): TelegramHttpResponse {
            calls++
            last = request
            return response
        }
    }
}
