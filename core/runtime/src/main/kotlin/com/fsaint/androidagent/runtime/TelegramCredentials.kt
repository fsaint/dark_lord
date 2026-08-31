package com.fsaint.androidagent.runtime

import com.fsaint.androidagent.model.PrincipalRole
import com.fsaint.androidagent.policy.Principal

/** Supplies the opaque Telegram Bot API token to the transport layer. */
interface TelegramBotTokenProvider {
    suspend fun apiToken(): String
}

/** Owner-controlled Telegram credential persistence boundary. */
interface TelegramBotCredentialStore : TelegramBotTokenProvider {
    suspend fun set(owner: Principal, token: String): CredentialOutcome
}

interface TelegramBotSecretStore {
    suspend fun read(): String?
    suspend fun write(value: String)
    suspend fun clear()
}

/** Validates and stores a Telegram bot token without exposing it to non-owners. */
class OwnerOnlyTelegramBotCredentialStore(
    private val secrets: TelegramBotSecretStore,
) : TelegramBotCredentialStore {
    override suspend fun apiToken(): String = secrets.read()
        ?.takeIf(::isValidToken)
        ?: throw IllegalStateException("Telegram bot token is not configured")

    override suspend fun set(owner: Principal, token: String): CredentialOutcome {
        val normalized = token.trim()
        if (owner.role != PrincipalRole.OWNER || !isValidToken(normalized)) {
            return CredentialOutcome.DENIED
        }
        return runCatching {
            secrets.write(normalized)
            CredentialOutcome.SAVED
        }.getOrElse { CredentialOutcome.FAILED }
    }

    private fun isValidToken(token: String): Boolean =
        token.length <= MAX_TOKEN_LENGTH && TOKEN_PATTERN.matches(token)

    private companion object {
        private const val MAX_TOKEN_LENGTH = 512
        private val TOKEN_PATTERN = Regex("^[0-9]{1,16}:[A-Za-z0-9_-]{20,}$")
    }
}
