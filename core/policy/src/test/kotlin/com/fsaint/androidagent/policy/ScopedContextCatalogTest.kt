package com.fsaint.androidagent.policy

import com.fsaint.androidagent.model.PrincipalRole
import kotlin.test.Test
import kotlin.test.assertEquals

class ScopedContextCatalogTest {
    @Test
    fun ownerReceivesRegisteredToolCatalog() {
        val scopes = ScopeRegistry()
        val owner = scopes.sessionFor(Principal("owner", null, PrincipalRole.OWNER), "TELEGRAM")

        val context = ScopedContextBuilder(
            scopes = scopes,
            memory = emptyMap(),
            availableTools = setOf("device.battery", "sms.reply"),
        ).build(owner)

        assertEquals(setOf("device.battery", "sms.reply"), context.resources)
    }

    @Test
    fun unknownReceivesOnlyPermittedCatalogEntries() {
        val scopes = ScopeRegistry()
        val unknown = scopes.sessionFor(Principal("unknown", null, PrincipalRole.UNKNOWN), "TELEGRAM")

        val context = ScopedContextBuilder(
            scopes = scopes,
            memory = emptyMap(),
            availableTools = setOf("device.battery", "sms.reply"),
        ).build(unknown)

        assertEquals(setOf("sms.reply"), context.resources)
    }

    @Test
    fun backgroundContextDoesNotAdvertiseSensorTools() {
        val scopes = ScopeRegistry()
        val owner = scopes.sessionFor(Principal("owner", null, PrincipalRole.OWNER), "TELEGRAM")

        val context = ScopedContextBuilder(
            scopes = scopes,
            memory = emptyMap(),
            availableTools = setOf("device.battery", "camera.capture", "microphone.record", "screen.capture"),
        ).build(owner)

        assertEquals(setOf("device.battery"), context.resources)
    }
}
