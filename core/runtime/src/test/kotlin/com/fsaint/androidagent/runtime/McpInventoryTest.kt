package com.fsaint.androidagent.runtime

import com.fsaint.androidagent.mcp.*
import com.fsaint.androidagent.model.*
import com.fsaint.androidagent.policy.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class McpInventoryTest {
    @Test fun inventoryToolReportsRealServersAndFailuresWithoutEndpoints() = runTest {
        val scopes = ScopeRegistry()
        val owner = scopes.sessionFor(Principal("owner", null, PrincipalRole.OWNER), "LOCAL_API")
        val manager = McpConnectionManager({ listOf(
            McpConnection("good", "Office", "https://good.test/private-path"),
            McpConnection("auth", "Protected", "https://auth.test/private-path"),
        ) }, scopes, LiveMcpClient(InventoryWire()))
        val prepared = McpConversationExtension(manager).prepare(owner)
        assertTrue("mcp_inventory" in prepared.definitions)
        val result = prepared.execute(ToolCall("mcp_inventory", mapOf(MCP_ARGUMENTS_KEY to "{}")))
        assertTrue(result.success)
        val payload = Json.parseToJsonElement(result.payload.toString()).jsonObject
        val servers = payload.getValue("servers").jsonArray.map { it.jsonObject }.associateBy { it.getValue("name").jsonPrimitive.content }
        assertEquals("READY", servers.getValue("Office").getValue("status").jsonPrimitive.content)
        assertEquals(1, servers.getValue("Office").getValue("toolCount").jsonPrimitive.int)
        assertEquals("AUTH_REQUIRED", servers.getValue("Protected").getValue("status").jsonPrimitive.content)
        assertEquals("whoami", payload.getValue("tools").jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content)
        assertFalse(result.payload.toString().contains("private-path"))
    }

    @Test fun deniedInventoryDoesNotContactOrRevealSavedServers() = runTest {
        val scopes = ScopeRegistry()
        val guest = scopes.sessionFor(Principal("guest", null, PrincipalRole.UNKNOWN), "SMS")
        val wire = InventoryWire()
        val manager = McpConnectionManager({ listOf(McpConnection("good", "Private server", "https://good.test")) }, scopes, LiveMcpClient(wire))
        val prepared = McpConversationExtension(manager).prepare(guest)
        val result = prepared.execute(ToolCall("mcp_inventory", mapOf(MCP_ARGUMENTS_KEY to "{}")))
        assertTrue(result.success)
        val payload = Json.parseToJsonElement(result.payload.toString()).jsonObject
        assertTrue(payload.getValue("servers").jsonArray.isEmpty())
        assertTrue(payload.getValue("tools").jsonArray.isEmpty())
        assertEquals(0, wire.requests)
    }

    private class InventoryWire : McpStreamTransport {
        var requests = 0
        override suspend fun exchange(request: McpExchangeRequest, onMessage: suspend (JsonObject) -> Boolean): McpStreamResponse {
            requests++
            if (request.url.contains("auth.test")) return McpStreamResponse(401)
            val result = when (request.body["method"]!!.jsonPrimitive.content) {
                "initialize" -> """{"protocolVersion":"2025-11-25","capabilities":{"tools":{}}}"""
                "notifications/initialized" -> return McpStreamResponse(202)
                "tools/list" -> """{"tools":[{"name":"whoami","inputSchema":{"type":"object"}}]}"""
                else -> error("Inventory must not call a remote tool")
            }
            onMessage(buildJsonObject { put("jsonrpc", "2.0"); put("id", request.body["id"]!!); put("result", Json.parseToJsonElement(result)) })
            return McpStreamResponse(200)
        }
    }
}
