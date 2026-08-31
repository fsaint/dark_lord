package com.fsaint.androidagent.runtime

import com.fsaint.androidagent.model.PrincipalRole
import com.fsaint.androidagent.policy.Principal
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TelegramCredentialsTest {
    private val validToken = "123456789:ABCDEFGHIJKLMNOPQRSTUVWXYZabcd"

    @Test
    fun ownerTokenIsTrimmedAndCanBeReadByProvider() = runTest {
        val secrets = FakeSecrets()
        val store = OwnerOnlyTelegramBotCredentialStore(secrets)

        assertEquals(CredentialOutcome.SAVED, store.set(owner(), "  $validToken  "))
        assertEquals(validToken, store.apiToken())
        assertEquals(validToken, secrets.value)
    }

    @Test
    fun nonOwnerCannotWriteTelegramToken() = runTest {
        val secrets = FakeSecrets()
        val store = OwnerOnlyTelegramBotCredentialStore(secrets)

        assertEquals(CredentialOutcome.DENIED, store.set(Principal("known", null, PrincipalRole.KNOWN), validToken))
        assertEquals(null, secrets.value)
    }

    @Test
    fun blankMalformedAndOversizedTokensAreRejected() = runTest {
        val store = OwnerOnlyTelegramBotCredentialStore(FakeSecrets())

        assertEquals(CredentialOutcome.DENIED, store.set(owner(), "   "))
        assertEquals(CredentialOutcome.DENIED, store.set(owner(), "not-a-telegram-token"))
        assertEquals(CredentialOutcome.DENIED, store.set(owner(), "1:${"a".repeat(512)}"))
    }

    @Test
    fun missingTokenCannotBeRead() = runTest {
        val store = OwnerOnlyTelegramBotCredentialStore(FakeSecrets())

        assertFailsWith<IllegalStateException> { store.apiToken() }
    }

    private fun owner() = Principal("owner", null, PrincipalRole.OWNER)

    private class FakeSecrets : TelegramBotSecretStore {
        var value: String? = null
        override suspend fun read(): String? = value
        override suspend fun write(value: String) { this.value = value }
        override suspend fun clear() { value = null }
    }
}
