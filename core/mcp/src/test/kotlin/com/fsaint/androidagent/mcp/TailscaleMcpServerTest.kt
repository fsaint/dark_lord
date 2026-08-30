package com.fsaint.androidagent.mcp

import com.fsaint.androidagent.model.PrincipalRole
import com.fsaint.androidagent.model.ToolError
import com.fsaint.androidagent.policy.Principal
import com.fsaint.androidagent.policy.ScopeRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TailscaleMcpServerTest {
    private val scopes = ScopeRegistry()
    private val owner = scopes.sessionFor(Principal("owner", "+14155550123", PrincipalRole.OWNER), "tailscale")
    private val router = ScopedMcpRouter(scopes, listOf(McpConnection("mail", "Mail", "https://mail.example.test"))) { _, _ -> McpCallResult.Success("ok") }

    @Test
    fun rejectsNonTailscaleAddressAndUnknownClient() = runTest {
        val server = TailscaleMcpServer(router, mapOf("client-1" to owner))
        assertEquals(ToolError.OS_RESTRICTED, server.handle("192.168.1.2", "client-1", request()).error)
        assertEquals(ToolError.PERMISSION_REQUIRED, server.handle("100.100.1.2", "unknown", request()).error)
    }

    @Test
    fun rejectsMalformedAndOutOfScopeRequests() = runTest {
        val server = TailscaleMcpServer(router, mapOf("client-1" to owner))
        assertEquals(ToolError.NOT_FOUND, server.handle("100.100.1.2", "client-1", "not-json").error)
        assertEquals(ToolError.NOT_FOUND, server.handle("100.100.1.2", "client-1", "{}" ).error)
    }

    @Test
    fun authorizedClientDelegatesThroughScopedRouter() = runTest {
        val server = TailscaleMcpServer(router, mapOf("client-1" to owner))
        val response = server.handle("100.100.1.2", "client-1", request())
        assertEquals("ok", response.payload)
    }

    @Test
    fun boundsRequestAndResponseSizes() = runTest {
        val server = TailscaleMcpServer(router, mapOf("client-1" to owner), maxRequestBytes = 20)
        assertEquals(ToolError.NETWORK_ERROR, server.handle("100.100.1.2", "client-1", request("x".repeat(100))).error)
    }

    private fun request(extra: String = "") = "{\"connection\":\"mail\",\"tool\":\"mail.search\",\"arguments\":{\"q\":\"$extra\"}}"
}
