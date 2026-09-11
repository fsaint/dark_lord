package com.fsaint.androidagent.mcp

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class LiveMcpClientTest {
    @Test fun allSupportedVersionsAreNegotiated() = runTest {
        for (version in listOf("2025-11-25", "2025-06-18", "2025-03-26")) {
            val wire = FixtureWire(version = version)
            assertEquals(version, LiveMcpClient(wire).discover("https://example.test").session.version)
            assertEquals(version, wire.lastHeaders["MCP-Protocol-Version"])
        }
    }

    @Test fun missingToolsCapabilityDoesNotAttemptListing() = runTest {
        val fixture = FixtureWire()
        val wire = object : McpStreamTransport {
            override suspend fun exchange(request: McpExchangeRequest, onMessage: suspend (JsonObject) -> Boolean): McpStreamResponse = fixture.exchange(request) { message ->
                val result = JsonObject(message["result"]!!.jsonObject + ("capabilities" to JsonObject(emptyMap())))
                onMessage(JsonObject(message + ("result" to result)))
            }
        }
        assertTrue(LiveMcpClient(wire).discover("https://example.test").tools.isEmpty())
        assertFalse(fixture.methods.contains("tools/list"))
    }

    @Test fun paginatedCatalogKeepsBothPagesAndPassesCursor() = runTest {
        val fixture = FixtureWire()
        var pages = 0
        val wire = object : McpStreamTransport {
            override suspend fun exchange(request: McpExchangeRequest, onMessage: suspend (JsonObject) -> Boolean): McpStreamResponse = fixture.exchange(request) { message ->
                if (request.body["method"] == JsonPrimitive("tools/list")) {
                    pages++
                    if (pages == 2) assertEquals(JsonPrimitive("page2"), request.body["params"]!!.jsonObject["cursor"])
                    val result = buildJsonObject {
                        put("tools", buildJsonArray { add(buildJsonObject { put("name", "tool$pages"); put("inputSchema", buildJsonObject { put("type", "object") }) }) })
                        if (pages == 1) put("nextCursor", "page2")
                    }
                    onMessage(JsonObject(message + ("result" to result)))
                } else onMessage(message)
            }
        }
        assertEquals(listOf("tool1", "tool2"), LiveMcpClient(wire).discover("https://example.test").tools.map { it.name })
    }

    @Test fun unsupportedServerRequestsAreRejectedAndCatalogNotificationsAreObserved() = runTest {
        val fixture = FixtureWire()
        var rejection: JsonObject? = null
        val wire = object : McpStreamTransport {
            override suspend fun exchange(request: McpExchangeRequest, onMessage: suspend (JsonObject) -> Boolean): McpStreamResponse {
                if (request.body["error"] != null) { rejection = request.body; return McpStreamResponse(202) }
                if (request.body["method"] == JsonPrimitive("tools/list")) {
                    onMessage(parseMcpObject("""{"jsonrpc":"2.0","id":"server-1","method":"sampling/createMessage","params":{}}"""))
                    onMessage(parseMcpObject("""{"jsonrpc":"2.0","method":"notifications/tools/list_changed"}"""))
                }
                return fixture.exchange(request, onMessage)
            }
        }
        val discovery = LiveMcpClient(wire).discover("https://example.test")
        assertTrue(discovery.session.toolsChanged)
        assertEquals(-32601, rejection!!["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        assertEquals(JsonPrimitive("server-1"), rejection!!["id"])
    }
    @Test fun malformedSchemasAreOmittedWithoutHidingValidTools() = runTest {
        val fixture = FixtureWire()
        val wire = object : McpStreamTransport {
            override suspend fun exchange(request: McpExchangeRequest, onMessage: suspend (JsonObject) -> Boolean): McpStreamResponse = fixture.exchange(request) { message ->
                if (request.body["method"] == JsonPrimitive("tools/list")) {
                    val schemas = listOf("""{"type":"object","properties":[]}""", """{"type":"object","properties":{"x":{"type":"bogus"}}}""", """{"type":"object","required":"x"}""")
                    val original = message["result"]!!.jsonObject["tools"]!!.jsonArray
                    onMessage(JsonObject(message + ("result" to buildJsonObject { put("tools", JsonArray(original + schemas.mapIndexed { index, schema -> buildJsonObject { put("name", "bad$index"); put("inputSchema", Json.parseToJsonElement(schema)) } })) })))
                } else onMessage(message)
            }
        }
        val discovery = LiveMcpClient(wire).discover("https://example.test")
        assertEquals(listOf("lookup"), discovery.tools.map { it.name })
        assertTrue(discovery.notices.isNotEmpty())
    }

    @Test fun endpointErrorsNeverEchoSecrets() {
        val error = assertFailsWith<IllegalArgumentException> { validateMcpEndpoint("https://example.test/?token=private secret") }
        assertFalse(error.message.orEmpty().contains("private"))
    }
    @Test fun discoveryNegotiatesSessionAndCallsKeepTypedArguments() = runTest {
        val wire = FixtureWire()
        val client = LiveMcpClient(wire)
        val discovery = client.discover("https://example.test/mcp")
        assertEquals(listOf("lookup"), discovery.tools.map { it.name })
        assertEquals(listOf("initialize", "notifications/initialized", "tools/list"), wire.methods)
        val args = Json.parseToJsonElement("""{"count":3,"enabled":true,"items":[1,null,{"q":"a&b=c"}]}""").jsonObject
        val result = client.call(discovery.session, "lookup", args)
        assertEquals(args, wire.arguments)
        assertEquals("remote result", result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("session-1", wire.lastHeaders["MCP-Session-Id"])
        assertEquals("2025-06-18", wire.lastHeaders["MCP-Protocol-Version"])
    }

    @Test fun authenticationAndUnsupportedVersionFailExplicitly() = runTest {
        assertFailsWith<McpFailure> { LiveMcpClient(FixtureWire(status = 401)).discover("https://example.test") }
        assertFailsWith<McpFailure> { LiveMcpClient(FixtureWire(version = "unknown")).discover("https://example.test") }
    }

    @Test fun rejectsUnsafeEndpointsWithoutNetwork() = runTest {
        val wire = FixtureWire()
        for (url in listOf("http://example.test", "https://user:pass@example.test", "https://example.test/#frag")) {
            assertFailsWith<IllegalArgumentException> { LiveMcpClient(wire).discover(url) }
        }
        assertTrue(wire.methods.isEmpty())
    }

    @Test fun malformedResponseIdsAndRepeatedPaginationDoNotBecomeTools() = runTest {
        assertFailsWith<McpFailure> { LiveMcpClient(FixtureWire(wrongId = true)).discover("https://example.test") }
        assertFailsWith<McpFailure> { LiveMcpClient(FixtureWire(repeatCursor = true)).discover("https://example.test") }
    }

    @Test fun cancellationIsNotConvertedToNetworkFailure() = runTest {
        val transport = object : McpStreamTransport {
            override suspend fun exchange(request: McpExchangeRequest, onMessage: suspend (JsonObject) -> Boolean): McpStreamResponse = throw CancellationException("stop")
        }
        assertFailsWith<CancellationException> { LiveMcpClient(transport).discover("https://example.test") }
    }
}

internal class FixtureWire(val status: Int = 200, val version: String = "2025-06-18", val wrongId: Boolean = false, val repeatCursor: Boolean = false) : McpStreamTransport {
    val methods = mutableListOf<String>()
    var arguments: JsonObject? = null
    var lastHeaders = emptyMap<String, String>()
    override suspend fun exchange(request: McpExchangeRequest, onMessage: suspend (JsonObject) -> Boolean): McpStreamResponse {
        val method = request.body["method"]?.jsonPrimitive?.content.orEmpty()
        methods += method
        lastHeaders = request.headers
        if (status != 200) return McpStreamResponse(status)
        if (method.startsWith("notifications/")) return McpStreamResponse(202)
        val result = when (method) {
            "initialize" -> """{"protocolVersion":"$version","capabilities":{"tools":{}},"serverInfo":{"name":"fixture","version":"1"}}"""
            "tools/list" -> """{"tools":[{"name":"lookup","description":"Look up data","inputSchema":{"type":"object","properties":{"count":{"type":"integer"}}}}]}""".let {
                if (repeatCursor) it.dropLast(1) + ",\"nextCursor\":\"again\"}" else it
            }
            "tools/call" -> {
                arguments = request.body["params"]!!.jsonObject["arguments"]!!.jsonObject
                """{"content":[{"type":"text","text":"remote result"}],"isError":false}"""
            }
            else -> error("Unexpected method: $method")
        }
        onMessage(buildJsonObject { put("jsonrpc", "2.0"); put("id", if (wrongId) JsonPrimitive(-99) else request.body["id"]!!); put("result", Json.parseToJsonElement(result)) })
        return McpStreamResponse(200, mapOf("MCP-Session-Id" to "session-1"))
    }
}
