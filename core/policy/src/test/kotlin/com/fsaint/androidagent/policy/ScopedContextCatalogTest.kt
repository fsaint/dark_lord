package com.fsaint.androidagent.policy

import com.fsaint.androidagent.model.PrincipalRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun telegramOwnerContextAdvertisesCameraButNotOtherSensors() {
        val scopes = ScopeRegistry()
        val owner = scopes.sessionFor(Principal("owner", null, PrincipalRole.OWNER), "TELEGRAM")

        val context = ScopedContextBuilder(
            scopes = scopes,
            memory = emptyMap(),
            availableTools = setOf("device.battery", "camera.capture", "microphone.record", "screen.capture"),
        ).build(owner)

        assertEquals(setOf("device.battery", "camera.capture"), context.resources)
    }

    @Test
    fun voiceOwnerContextIsSupersetOfTelegramOwnerContext() {
        val scopes = ScopeRegistry()
        val owner = Principal("owner", null, PrincipalRole.OWNER)
        val builder = ScopedContextBuilder(
            scopes = scopes,
            memory = emptyMap(),
            availableTools = setOf("device.battery", "sms.reply", "camera.capture", "microphone.record", "screen.capture", "browser.open"),
            availableMcps = setOf("mcp-a"),
            availableSkills = setOf("skill-a"),
        )

        val voice = builder.build(scopes.sessionFor(owner, "VOICE"))
        val telegram = builder.build(scopes.sessionFor(owner, "TELEGRAM"))

        assertTrue(voice.resources.containsAll(telegram.resources), "voice tools ${voice.resources} must include telegram tools ${telegram.resources}")
        assertTrue("microphone.record" in voice.resources)
        assertTrue("screen.capture" in voice.resources)
        assertEquals(telegram.mcpResources, voice.mcpResources)
        assertEquals(telegram.skillResources, voice.skillResources)
    }
}
