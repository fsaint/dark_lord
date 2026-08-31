package com.fsaint.androidagent.runtime

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TelegramBotClientTest {
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
    fun parsesTextUpdatesAndIgnoresNonTextUpdates() = runTest {
        val transport = FakeTransport(TelegramHttpResponse(200, """
            {"ok":true,"result":[
              {"update_id":10,"message":{"chat":{"id":99},"text":"hello"}},
              {"update_id":11,"message":{"chat":{"id":99},"photo":[]}}
            ]}
        """.trimIndent()))

        assertEquals(listOf(TelegramUpdate(10, "99", "hello")), client(transport).getUpdates(9, 12))
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

    private fun client(transport: FakeTransport) = TelegramBotClient(transport, StaticToken(token))

    private class StaticToken(private val value: String) : TelegramBotTokenProvider {
        override suspend fun apiToken(): String = value
    }

    private class MissingToken : TelegramBotTokenProvider {
        override suspend fun apiToken(): String = error("missing")
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
