package com.fsaint.androidagent.runtime

import com.fsaint.androidagent.model.PrincipalRole
import com.fsaint.androidagent.policy.Principal
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OpenAiCredentialStoreTest {
    @Test
    fun onlyOwnerMaySetOrReadApiKeyAndSecretStoreHoldsOpaqueValue() = runTest {
        val secrets = FakeSecrets()
        val store = OwnerOnlyOpenAiCredentialStore(secrets)
        val owner = Principal("owner", null, PrincipalRole.OWNER)
        val known = Principal("known", null, PrincipalRole.KNOWN)

        assertEquals(CredentialOutcome.DENIED, store.set(known, "sk-nope"))
        assertEquals(CredentialOutcome.SAVED, store.set(owner, "sk-real"))
        assertNull(store.get(known))
        assertEquals("sk-real", store.get(owner))
        assertEquals("sk-real", secrets.value)
    }

    @Test
    fun stripsWhitespaceFromPastedApiKeys() = runTest {
        val secrets = FakeSecrets()
        val store = OwnerOnlyOpenAiCredentialStore(secrets)
        val owner = Principal("owner", null, PrincipalRole.OWNER)

        assertEquals(CredentialOutcome.SAVED, store.set(owner, "  sk-real\r\nmore \t"))
        assertEquals("sk-realmore", secrets.value)
    }

    private class FakeSecrets : OpenAiSecretStore {
        var value: String? = null
        override suspend fun read(): String? = value
        override suspend fun write(value: String) { this.value = value }
        override suspend fun clear() { value = null }
    }
}
