package com.fsaint.androidagent.mcp

import com.fsaint.androidagent.model.ToolError
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OAuthManagerTest {
    @Test
    fun exchangesAuthorizationCodeAndStoresRefreshTokenOutsideRegularState() = runTest {
        val http = FakeMcpHttp { request ->
            assertEquals("https", request.url.substringBefore("://"))
            McpHttpResponse(200, "{\"access_token\":\"access-1\",\"refresh_token\":\"refresh-1\",\"expires_in\":60}")
        }
        val secrets = FakeSecretStore()
        val manager = OAuthManager(http, secrets, nowEpochSeconds = { 100 })

        val token = manager.exchangeAuthorizationCode(OAuthProvider("https://auth.example.test/token", "client-1"), "code-1")

        assertEquals("access-1", token.accessToken)
        assertEquals(160, token.expiresAtEpochSeconds)
        assertEquals("refresh-1", secrets.values["oauth:client-1"])
    }

    @Test
    fun refreshesExpiredAccessTokenAndDoesNotReturnRefreshSecret() = runTest {
        val http = FakeMcpHttp { McpHttpResponse(200, "{\"access_token\":\"access-2\",\"expires_in\":120}") }
        val secrets = FakeSecretStore(mapOf("oauth:client-1" to "refresh-1"))
        val manager = OAuthManager(http, secrets, nowEpochSeconds = { 200 })

        val token = manager.refresh(OAuthProvider("https://auth.example.test/token", "client-1"))

        assertEquals("access-2", token.accessToken)
        assertEquals(320, token.expiresAtEpochSeconds)
        assertTrue(token.toString().contains("refresh-1") == false)
        assertTrue(token.isExpired(321))
    }

    @Test
    fun reportsSecretStoreFailureWithoutReturningToken() = runTest {
        val manager = OAuthManager(FakeMcpHttp { McpHttpResponse(200, "{\"access_token\":\"a\",\"refresh_token\":\"r\",\"expires_in\":60}") }, FailingSecretStore())

        assertEquals(ToolError.NETWORK_ERROR, manager.exchangeAuthorizationCodeResult(OAuthProvider("https://auth.example.test/token", "c"), "x").error)
    }

    @Test
    fun rejectsInsecureOversizedAndMalformedResponses() = runTest {
        val insecure = OAuthManager(FakeMcpHttp { error("should not call") }, FakeSecretStore())
        assertEquals(ToolError.NETWORK_ERROR, insecure.exchangeAuthorizationCodeResult(OAuthProvider("http://auth.example.test/token", "c"), "x").error)

        val oversized = OAuthManager(FakeMcpHttp { McpHttpResponse(200, "x".repeat(100)) }, FakeSecretStore(), maxBodyBytes = 32)
        assertEquals(ToolError.NETWORK_ERROR, oversized.exchangeAuthorizationCodeResult(OAuthProvider("https://auth.example.test/token", "c"), "x").error)
    }

    private class FakeMcpHttp(private val responder: (McpHttpRequest) -> McpHttpResponse) : McpHttpTransport {
        override suspend fun execute(request: McpHttpRequest): McpHttpResponse = responder(request)
    }

    private class FakeSecretStore(initial: Map<String, String> = emptyMap()) : OAuthSecretStore {
        val values = initial.toMutableMap()
        override suspend fun read(key: String): String? = values[key]
        override suspend fun write(key: String, value: String) { values[key] = value }
    }

    private class FailingSecretStore : OAuthSecretStore {
        override suspend fun read(key: String): String? = null
        override suspend fun write(key: String, value: String): Nothing = error("keystore unavailable")
    }
}
