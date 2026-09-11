package com.fsaint.androidagent.runtime

import com.fsaint.androidagent.mcp.*
import com.fsaint.androidagent.model.*
import com.fsaint.androidagent.policy.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class LiveMcpHarnessTest {
    @Test fun savedServerToolsReachModelAndTypedCallReturnsThroughHarness() = runTest {
        val scopes = ScopeRegistry()
        val owner = scopes.sessionFor(Principal("owner", null, PrincipalRole.OWNER), "TELEGRAM")
        var remoteArguments: JsonObject? = null
        val args = """{"count":3,"enabled":true,"items":[null,{"q":"a&b=c"}]}"""
        val wire = object : McpStreamTransport {
            override suspend fun exchange(request: McpExchangeRequest, onMessage: suspend (JsonObject) -> Boolean): McpStreamResponse {
                val result = when (request.body["method"]!!.jsonPrimitive.content) {
                    "initialize" -> """{"protocolVersion":"2025-11-25","capabilities":{"tools":{}},"serverInfo":{"name":"fixture","version":"1"}}"""
                    "notifications/initialized" -> return McpStreamResponse(202)
                    "tools/list" -> """{"tools":[{"name":"lookup","description":"Look up items","inputSchema":{"type":"object","properties":{"count":{"type":"integer"},"enabled":{"type":"boolean"},"items":{"type":"array","items":{}}},"required":["count"]}}]}"""
                    "tools/call" -> {
                        assertEquals("lookup", request.body["params"]!!.jsonObject["name"]!!.jsonPrimitive.content)
                        remoteArguments = request.body["params"]!!.jsonObject["arguments"]!!.jsonObject
                        """{"content":[{"type":"text","text":"REMOTE_FIXTURE_RESULT"}],"isError":false}"""
                    }
                    else -> error("Unexpected RPC")
                }
                onMessage(buildJsonObject { put("jsonrpc", "2.0"); put("id", request.body["id"]!!); put("result", Json.parseToJsonElement(result)) })
                return McpStreamResponse(200)
            }
        }
        val manager = McpConnectionManager({ listOf(McpConnection("saved", "My saved server", "https://example.test/mcp")) }, scopes, LiveMcpClient(wire))
        var modelCalls = 0
        val model = OpenAiHttpClient(object : OpenAiHttpTransport {
            override suspend fun execute(request: OpenAiHttpRequest): OpenAiHttpResponse {
                val body = Json.parseToJsonElement(request.body).jsonObject
                val tool = body["tools"]!!.jsonArray.map { it.jsonObject }.single { it["name"]!!.jsonPrimitive.content.startsWith("mcp_") && it["name"] != JsonPrimitive("mcp_inventory") }
                assertEquals("integer", tool["parameters"]!!.jsonObject["properties"]!!.jsonObject["count"]!!.jsonObject["type"]!!.jsonPrimitive.content)
                assertTrue(tool["description"]!!.jsonPrimitive.content.contains("My saved server"))
                return if (modelCalls++ == 0) OpenAiHttpResponse(200, buildJsonObject {
                    put("output", buildJsonArray { add(buildJsonObject { put("type", "function_call"); put("name", tool["name"]!!); put("arguments", args) }) })
                }.toString()) else {
                    assertTrue(request.body.contains("REMOTE_FIXTURE_RESULT"))
                    OpenAiHttpResponse(200, """{"output_text":"The remote lookup worked."}""")
                }
            }
        }, object : OpenAiApiKeyProvider { override suspend fun apiKey() = "sk-test-only" })
        val harness = ConversationHarness(model, ScopedToolRouter(emptyMap(), scopes), extension = McpConversationExtension(manager))
        val result = harness.run(ConversationRequest(owner, AgentEvent("test", "chat", "TELEGRAM", 0, emptyMap()), AgentContext(emptySet(), emptyMap()), "Look up items"))
        assertEquals(Json.parseToJsonElement(args).jsonObject, remoteArguments)
        assertEquals("The remote lookup worked.", result.response)
        assertEquals(1, result.toolCalls.size)
    }
}
