package com.fsaint.androidagent.mcp

import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class McpTransportCancellationTest {
    @Test fun cancellationDisconnectsAnOpenStreamAndStopsTheCaller() = runBlocking {
        val reading = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val connection = object : HttpURLConnection(URL("https://example.test/mcp")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() { disconnected.countDown() }
            override fun getOutputStream() = ByteArrayOutputStream()
            override fun getResponseCode() = 200
            override fun getHeaderFields(): Map<String, List<String>> = emptyMap()
            override fun getContentType() = "text/event-stream"
            override fun getInputStream() = object : InputStream() {
                override fun read(): Int {
                    reading.countDown()
                    check(disconnected.await(3, TimeUnit.SECONDS)) { "Stream was never disconnected" }
                    return -1
                }
            }
        }
        val transport = UrlConnectionMcpTransport { connection }
        val pending = launch(Dispatchers.Default) { transport.exchange(McpExchangeRequest("https://example.test/mcp", emptyMap(), JsonObject(emptyMap()))) { false } }
        try {
            assertTrue(withContext(Dispatchers.IO) { reading.await(3, TimeUnit.SECONDS) })
            withTimeout(1_000) { pending.cancelAndJoin() }
            assertTrue(withContext(Dispatchers.IO) { disconnected.await(3, TimeUnit.SECONDS) })
            assertFalse(connection.instanceFollowRedirects)
        } finally { pending.cancel(); connection.disconnect() }
    }
}
