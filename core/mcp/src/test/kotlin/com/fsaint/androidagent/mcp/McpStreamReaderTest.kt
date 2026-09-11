package com.fsaint.androidagent.mcp

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import kotlin.test.*

class McpStreamReaderTest {
    @Test fun malformedJsonFailsAndTruncatedSseCannotBecomeAResult() = runTest {
        assertFailsWith<McpFailure> { McpStreamReader.read(ByteArrayInputStream("{broken}".toByteArray()), "application/json") { true } }
        var messages = 0
        McpStreamReader.read(ByteArrayInputStream("data: {\"id\":1,\"result\":{}}".toByteArray()), "text/event-stream") { messages++; true }
        assertEquals(0, messages)
    }
    @Test fun multilineSseSkipsCommentsAndStopsAtMatchingResponse() = runTest {
        val input = ": ping\n\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notice\"}\n\ndata: {\"jsonrpc\":\"2.0\",\n" +
            "data: \"id\":7,\"result\":{}}\n\ninvalid unread tail"
        val messages = mutableListOf<JsonObject>()
        McpStreamReader.read(ByteArrayInputStream(input.toByteArray()), "text/event-stream") { messages += it; it["id"] == JsonPrimitive(7) }
        assertEquals(2, messages.size)
        assertEquals(7, messages.last()["id"]!!.jsonPrimitive.int)
    }
    @Test fun rejectsOversizedStreamsAndDeepJsonBeforeParsing() = runTest {
        assertFailsWith<McpFailure> { McpStreamReader.read(ByteArrayInputStream(ByteArray(1_048_577) { 32 }), "application/json") { true } }
        assertFailsWith<McpFailure> { parseMcpObject("[".repeat(65) + "]".repeat(65)) }
    }
    @Test fun jsonResponseIsParsedAndUnsupportedContentTypeIsRejected() = runTest {
        var result: JsonObject? = null
        McpStreamReader.read(ByteArrayInputStream("""{"jsonrpc":"2.0","id":1,"result":{}}""".toByteArray()), "application/json; charset=utf-8") { result = it; true }
        assertEquals(1, result!!["id"]!!.jsonPrimitive.int)
        assertFailsWith<McpFailure> { McpStreamReader.read(ByteArrayInputStream(byteArrayOf()), "text/html") { true } }
    }
}
