package com.fsaint.androidagent.mcp

import com.fsaint.androidagent.model.ToolError

data class OAuthProvider(val tokenEndpoint: String, val clientId: String)
data class OAuthToken(val accessToken: String, val expiresAtEpochSeconds: Long) {
    fun isExpired(nowEpochSeconds: Long, leewaySeconds: Long = 0): Boolean = nowEpochSeconds + leewaySeconds >= expiresAtEpochSeconds
}
data class OAuthResult(val token: OAuthToken? = null, val error: ToolError? = null)

interface OAuthSecretStore {
    suspend fun read(key: String): String?
    suspend fun write(key: String, value: String)
}

class OAuthManager(
    private val http: McpHttpTransport,
    private val secrets: OAuthSecretStore,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val maxBodyBytes: Int = 64 * 1024,
) {
    suspend fun exchangeAuthorizationCode(provider: OAuthProvider, code: String): OAuthToken =
        exchangeAuthorizationCodeResult(provider, code).token ?: throw OAuthException(ToolError.NETWORK_ERROR)

    suspend fun exchangeAuthorizationCodeResult(provider: OAuthProvider, code: String): OAuthResult =
        request(provider, "grant_type=authorization_code&code=${encode(code)}")

    suspend fun refresh(provider: OAuthProvider): OAuthToken =
        refreshResult(provider).token ?: throw OAuthException(ToolError.NETWORK_ERROR)

    suspend fun refreshResult(provider: OAuthProvider): OAuthResult {
        val refresh = secrets.read(secretKey(provider)) ?: return OAuthResult(error = ToolError.PERMISSION_REQUIRED)
        return request(provider, "grant_type=refresh_token&refresh_token=${encode(refresh)}")
    }

    private suspend fun request(provider: OAuthProvider, body: String): OAuthResult {
        if (!provider.tokenEndpoint.startsWith("https://")) return OAuthResult(error = ToolError.NETWORK_ERROR)
        val response = runCatching {
            http.execute(McpHttpRequest(provider.tokenEndpoint, mapOf("Content-Type" to "application/x-www-form-urlencoded"), body))
        }.getOrElse { return OAuthResult(error = ToolError.NETWORK_ERROR) }
        if (response.body.toByteArray().size > maxBodyBytes || response.status !in 200..299) return OAuthResult(error = ToolError.NETWORK_ERROR)
        val access = field(response.body, "access_token") ?: return OAuthResult(error = ToolError.NETWORK_ERROR)
        val expires = field(response.body, "expires_in")?.toLongOrNull()?.coerceAtLeast(1) ?: return OAuthResult(error = ToolError.NETWORK_ERROR)
        field(response.body, "refresh_token")?.let {
            try { secrets.write(secretKey(provider), it) } catch (_: Throwable) { return OAuthResult(error = ToolError.NETWORK_ERROR) }
        }
        return OAuthResult(OAuthToken(access, nowEpochSeconds() + expires))
    }

    private fun secretKey(provider: OAuthProvider) = "oauth:${provider.clientId}"
    private fun encode(value: String) = java.net.URLEncoder.encode(value, Charsets.UTF_8)
    private fun field(json: String, name: String): String? = Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*(?:\\\"([^\\\"]*)\\\"|([0-9]+))").find(json)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
}

class OAuthException(val error: ToolError) : RuntimeException(error.name)
