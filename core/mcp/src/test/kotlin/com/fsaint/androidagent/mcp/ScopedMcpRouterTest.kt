package com.fsaint.androidagent.mcp

import com.fsaint.androidagent.model.PrincipalRole
import com.fsaint.androidagent.model.ToolError
import com.fsaint.androidagent.policy.Principal
import com.fsaint.androidagent.policy.ResourceType
import com.fsaint.androidagent.policy.ScopeRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScopedMcpRouterTest {
    private val scopes = ScopeRegistry()
    private val connection = McpConnection("mail", "Personal mail", "https://mcp.example.test")
    private val owner = scopes.sessionFor(Principal("owner", "+14155550123", PrincipalRole.OWNER), "local")
    private val unknown = scopes.sessionFor(Principal("guest", null, PrincipalRole.UNKNOWN), "sms")

    @Test
    fun ungrantedPrincipalCannotDiscoverOrCallPersonalConnection() = runTest {
        val router = ScopedMcpRouter(scopes, listOf(connection)) { _, _ -> McpCallResult.Success("secret") }

        assertTrue(router.discover(unknown).isEmpty())
        assertEquals(ToolError.SCOPE_DENIED, router.execute(unknown, McpToolCall("mail", "mail.search")).error)
    }

    @Test
    fun ownerCanDiscoverAndCallEnrolledConnection() = runTest {
        val router = ScopedMcpRouter(scopes, listOf(connection)) { _, call -> McpCallResult.Success(call.name) }

        assertEquals(listOf(connection.copy(endpoint = "")), router.discover(owner))
        val result = router.execute(owner, McpToolCall("mail", "mail.search"))

        assertEquals("mail.search", result.payload)
    }

    @Test
    fun knownPrincipalCanUseExplicitConnectionGrantAndErrorsDoNotLeakSecrets() = runTest {
        val principal = scopes.sessionFor(Principal("known", "+14155550100", PrincipalRole.KNOWN), "local")
        scopes.grant(principal.principalId, ResourceType.MCP, "mail")
        val router = ScopedMcpRouter(scopes, listOf(connection)) { _, _ -> McpCallResult.NetworkError("token=do-not-return") }

        assertEquals(listOf(connection.copy(endpoint = "")), router.discover(principal))
        val result = router.execute(principal, McpToolCall("mail", "mail.search"))
        assertEquals(ToolError.NETWORK_ERROR, result.error)
        assertTrue(result.message?.contains("token") != true)
    }
}
