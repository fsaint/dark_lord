package com.fsaint.androidagent.mcp

import com.fsaint.androidagent.model.*
import com.fsaint.androidagent.policy.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class McpConnectionManagerTest {
    private val scopes = ScopeRegistry()
    private val owner = scopes.sessionFor(Principal("owner", null, PrincipalRole.OWNER), "LOCAL_API")
    private val guest = scopes.sessionFor(Principal("guest", null, PrincipalRole.UNKNOWN), "SMS")
    @Test fun malformedToolResultsNeverReportVerifiedSuccess() = runTest {
        for (body in listOf("{}", """{"content":"bad","isError":{}}""", """{"content":[],"isError":"false"}""", """{"content":[],"structuredContent":[]}""", """{"content":[{"type":"text"}]}""", """{"content":[{"type":"text","text":3}]}""")) {
            val fixture = FixtureWire()
            val wire = object : McpStreamTransport {
                override suspend fun exchange(request: McpExchangeRequest, onMessage: suspend (JsonObject) -> Boolean): McpStreamResponse = fixture.exchange(request) { message ->
                    if (request.body["method"] == JsonPrimitive("tools/call")) onMessage(JsonObject(message + ("result" to parseMcpObject(body)))) else onMessage(message)
                }
            }
            val manager = McpConnectionManager({ listOf(McpConnection("saved", "Server", "https://example.test")) }, scopes, LiveMcpClient(wire))
            val snapshot = manager.snapshot(owner)
            val result = manager.execute(owner, snapshot, ToolCall(snapshot.tools.keys.single(), mapOf(MCP_ARGUMENTS_KEY to "{}")))
            assertFalse(result.success, body)
            assertTrue(result.payload.toString().contains("Do not automatically repeat"))
            assertNotEquals(VerificationState.VERIFIED, result.verification)
        }
    }
    @Test fun aSingleOversizedTextResultIsExplicitlyMarked() = runTest {
        val fixture = FixtureWire()
        val wire = object : McpStreamTransport {
            override suspend fun exchange(request: McpExchangeRequest, onMessage: suspend (JsonObject) -> Boolean): McpStreamResponse = fixture.exchange(request) { message ->
                if (request.body["method"] == JsonPrimitive("tools/call")) onMessage(JsonObject(message + ("result" to buildJsonObject { put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "x".repeat(30_000)) }) }) }))) else onMessage(message)
            }
        }
        val manager = McpConnectionManager({ listOf(McpConnection("saved", "Server", "https://example.test")) }, scopes, LiveMcpClient(wire))
        val snapshot = manager.snapshot(owner)
        assertTrue(manager.execute(owner, snapshot, ToolCall(snapshot.tools.keys.single(), mapOf(MCP_ARGUMENTS_KEY to "{}"))).payload.toString().endsWith("[Result truncated]"))
    }

    @Test fun expiredSessionsAreNotReplayedAndARefreshGetsANewSession() = runTest {
        val fixture = FixtureWire()
        var calls = 0
        val wire = object : McpStreamTransport {
            override suspend fun exchange(request: McpExchangeRequest, onMessage: suspend (JsonObject) -> Boolean): McpStreamResponse {
                if (request.body["method"] == JsonPrimitive("tools/call")) { calls++; return McpStreamResponse(404) }
                return fixture.exchange(request, onMessage)
            }
        }
        val manager = McpConnectionManager({ listOf(McpConnection("saved", "Server", "https://example.test")) }, scopes, LiveMcpClient(wire))
        val snapshot = manager.snapshot(owner)
        val call = ToolCall(snapshot.tools.keys.single(), mapOf(MCP_ARGUMENTS_KEY to "{}"))
        assertFalse(manager.execute(owner, snapshot, call).success)
        assertEquals("SESSION_EXPIRED", manager.states.value["saved"]!!.status)
        assertFalse(manager.execute(owner, snapshot, call).success)
        assertEquals(1, calls)
        manager.refresh(owner, "saved")
        assertEquals(2, fixture.methods.count { it == "initialize" })
        assertEquals("READY", manager.states.value["saved"]!!.status)
    }

    @Test fun identicalToolNamesOnDifferentServersHaveDistinctStableAliases() = runTest {
        val manager = McpConnectionManager({ listOf(McpConnection("one", "One", "https://one.test"), McpConnection("two", "Two", "https://two.test")) }, scopes, LiveMcpClient(FixtureWire()))
        val snapshot = manager.snapshot(owner)
        assertEquals(2, snapshot.tools.size)
        assertEquals(setOf("lookup"), snapshot.tools.values.map { it.tool.name }.toSet())
        assertEquals(snapshot.tools.keys, manager.snapshot(owner).tools.keys)
    }
    @Test fun cancelledDiscoveryDoesNotLeaveConnectingEvenWhenConfigurationReadsAreCancellable() = runTest {
        val started = CompletableDeferred<Unit>()
        val wire = object : McpStreamTransport {
            override suspend fun exchange(request: McpExchangeRequest, onMessage: suspend (JsonObject) -> Boolean): McpStreamResponse { started.complete(Unit); awaitCancellation() }
        }
        val manager = McpConnectionManager({ currentCoroutineContext().ensureActive(); listOf(McpConnection("saved", "Server", "https://example.test")) }, scopes, LiveMcpClient(wire))
        val job = launch { manager.snapshot(owner) }
        started.await(); job.cancelAndJoin()
        assertEquals("UNREACHABLE", manager.states.value["saved"]!!.status)
    }

    @Test fun callTimeoutReturnsUnknownOutcomeWithoutReplay() = runTest {
        val fixture = FixtureWire()
        var calls = 0
        val wire = object : McpStreamTransport {
            override suspend fun exchange(request: McpExchangeRequest, onMessage: suspend (JsonObject) -> Boolean): McpStreamResponse {
                if (request.body["method"] == JsonPrimitive("tools/call")) { calls++; delay(61_000) }
                return fixture.exchange(request, onMessage)
            }
        }
        val manager = McpConnectionManager({ listOf(McpConnection("saved", "Server", "https://example.test")) }, scopes, LiveMcpClient(wire))
        val snapshot = manager.snapshot(owner)
        val result = manager.execute(owner, snapshot, ToolCall(snapshot.tools.keys.single(), mapOf(MCP_ARGUMENTS_KEY to "{}")))
        assertFalse(result.success)
        assertTrue(result.payload.toString().contains("Do not automatically repeat"))
        assertEquals(1, calls)
    }

    @Test fun oversizedSingleResultHasTruncationMarkerAndBinaryIsNotForwarded() = runTest {
        val fixture = FixtureWire()
        val wire = object : McpStreamTransport {
            override suspend fun exchange(request: McpExchangeRequest, onMessage: suspend (JsonObject) -> Boolean): McpStreamResponse = fixture.exchange(request) { message ->
                if (request.body["method"] == JsonPrimitive("tools/call")) onMessage(JsonObject(message + ("result" to buildJsonObject { put("content", buildJsonArray {
                    add(buildJsonObject { put("type", "image"); put("data", "SECRET_BASE64"); put("mimeType", "image/png") })
                    add(buildJsonObject { put("type", "text"); put("text", "x".repeat(30_000)) })
                }); put("isError", true) }))) else onMessage(message)
            }
        }
        val manager = McpConnectionManager({ listOf(McpConnection("saved", "Server", "https://example.test")) }, scopes, LiveMcpClient(wire))
        val snapshot = manager.snapshot(owner)
        val result = manager.execute(owner, snapshot, ToolCall(snapshot.tools.keys.single(), mapOf(MCP_ARGUMENTS_KEY to "{}")))
        assertFalse(result.success)
        assertFalse(result.payload.toString().contains("SECRET_BASE64"))
        assertTrue(result.payload.toString().contains("[Result truncated]"))
    }
    @Test fun oneDiscoveryTimeoutDoesNotHideTheHealthyServer() = runTest {
        val fixture = FixtureWire()
        val transport = object : McpStreamTransport {
            override suspend fun exchange(request: McpExchangeRequest, onMessage: suspend (kotlinx.serialization.json.JsonObject) -> Boolean): McpStreamResponse {
                if (request.url.contains("slow")) delay(11_000)
                return fixture.exchange(request, onMessage)
            }
        }
        val manager = McpConnectionManager({ listOf(McpConnection("slow", "Slow", "https://slow.test"), McpConnection("good", "Good", "https://good.test")) }, scopes, LiveMcpClient(transport))
        assertEquals("good", manager.snapshot(owner).tools.values.single().connection.id)
        assertEquals("UNREACHABLE", manager.states.value["slow"]!!.status)
    }
    @Test fun removalDuringFinalConfigurationReadCannotRepublishDiscovery() = runTest {
        val saved = listOf(McpConnection("saved", "Server", "https://example.test"))
        var configs = saved
        var reads = 0
        val paused = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val manager = McpConnectionManager({
            val value = configs
            if (++reads == 3) { paused.complete(Unit); release.await() }
            value
        }, scopes, LiveMcpClient(FixtureWire()))
        val pending = async { manager.snapshot(owner) }
        paused.await(); configs = emptyList(); manager.invalidate("saved"); release.complete(Unit)
        assertTrue(pending.await().tools.isEmpty())
        assertTrue(manager.states.value.isEmpty())
    }
    @Test fun removalDuringCallConfigurationReadPreventsDispatch() = runTest {
        var configs = listOf(McpConnection("saved", "Server", "https://example.test"))
        var pause = false
        val paused = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val wire = FixtureWire()
        val manager = McpConnectionManager({ val value = configs; if (pause) { paused.complete(Unit); release.await() }; value }, scopes, LiveMcpClient(wire))
        val snapshot = manager.snapshot(owner)
        pause = true
        val pending = async { manager.execute(owner, snapshot, ToolCall(snapshot.tools.keys.single(), mapOf(MCP_ARGUMENTS_KEY to "{}"))) }
        paused.await(); configs = emptyList(); manager.invalidate("saved"); release.complete(Unit)
        assertFalse(pending.await().success)
        assertFalse(wire.methods.contains("tools/call"))
    }
    @Test fun savedConfigurationDiscoversCachesAndDispatchesScopedTools() = runTest {
        val wire = FixtureWire()
        val manager = McpConnectionManager({ listOf(McpConnection("saved", "My server", "https://example.test")) }, scopes, LiveMcpClient(wire))
        assertTrue(manager.snapshot(guest).tools.isEmpty())
        assertTrue(wire.methods.isEmpty())
        val snapshot = manager.snapshot(owner)
        val alias = snapshot.tools.keys.single()
        manager.snapshot(owner)
        assertEquals(1, wire.methods.count { it == "initialize" })
        val call = ToolCall(alias, mapOf(MCP_ARGUMENTS_KEY to """{"count":3}"""))
        assertEquals(ToolError.SCOPE_DENIED, manager.execute(guest, snapshot, call).error)
        assertEquals(0, wire.methods.count { it == "tools/call" })
        assertTrue(manager.execute(owner, snapshot, call).payload.toString().contains("remote result"))
        assertEquals("READY", manager.states.value["saved"]!!.status)
    }
    @Test fun removalAndForgedAliasCannotReachTheServer() = runTest {
        val wire = FixtureWire()
        var configs = listOf(McpConnection("saved", "Server", "https://example.test"))
        val manager = McpConnectionManager({ configs }, scopes, LiveMcpClient(wire))
        val snapshot = manager.snapshot(owner)
        assertFalse(manager.execute(owner, snapshot, ToolCall("mcp_forged")).success)
        configs = emptyList(); manager.invalidate("saved")
        assertFalse(manager.execute(owner, snapshot, ToolCall(snapshot.tools.keys.single(), mapOf(MCP_ARGUMENTS_KEY to "{}"))).success)
        assertFalse(wire.methods.contains("tools/call"))
        assertTrue(manager.states.value.isEmpty())
    }
    @Test fun authFailureIsVisibleWithoutBreakingOtherServers() = runTest {
        val wire = FixtureWire(status = 401)
        val manager = McpConnectionManager({ listOf(McpConnection("saved", "Server", "https://example.test")) }, scopes, LiveMcpClient(wire))
        assertTrue(manager.snapshot(owner).tools.isEmpty())
        assertEquals("AUTH_REQUIRED", manager.states.value["saved"]!!.status)
    }
}
