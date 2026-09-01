package com.fsaint.androidagent.policy

import com.fsaint.androidagent.model.PrincipalRole
import com.fsaint.androidagent.model.ToolCall
import com.fsaint.androidagent.model.ToolError
import com.fsaint.androidagent.model.ToolResult
import com.fsaint.androidagent.model.VerificationState
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScopedToolRouterTest {
    @Test
    fun principalLookupNormalizesUsInboundNumbersToE164() {
        val owner = Principal("owner", "+14155550123", PrincipalRole.OWNER)

        assertEquals(owner, PrincipalRegistry(listOf(owner)).lookup("(415) 555-0123"))
    }

    @Test
    fun ownerCanExecuteAnyRegisteredTool() = runTest {
        val handler: suspend (ToolCall) -> ToolResult<Any> = { ToolResult(true, "72%", verification = VerificationState.VERIFIED) }
        val owner = ScopeRegistry().sessionFor(Principal("owner", "+14155550123", PrincipalRole.OWNER), "local")

        val result = ScopedToolRouter(mapOf("device.battery" to handler)).execute(owner, ToolCall("device.battery"))

        assertTrue(result.success)
        assertEquals("72%", result.payload)
    }

    @Test
    fun backgroundChannelsCannotExecuteSensorToolsForNonOwners() = runTest {
        val calls = mutableListOf<String>()
        val handler: suspend (ToolCall) -> ToolResult<Any> = { call ->
            calls += call.name
            ToolResult(true, "captured", verification = VerificationState.VERIFIED)
        }
        val router = ScopedToolRouter(
            mapOf(
                "camera.capture" to handler,
                "microphone.record" to handler,
                "screen.capture" to handler,
            ),
        )
        val scopes = ScopeRegistry()
        val owner = Principal("known", "+14155550123", PrincipalRole.KNOWN)

        listOf("TELEGRAM", "SMS", "NOTIFICATION").forEach { channel ->
            listOf("camera.capture", "microphone.record", "screen.capture").forEach { tool ->
                val result = router.execute(scopes.sessionFor(owner, channel), ToolCall(tool))

                assertEquals(ToolError.SCOPE_DENIED, result.error, "$channel must deny $tool")
            }
        }
        assertTrue(calls.isEmpty())
    }

    @Test
    fun ownerCanRequestAnySensorCaptureRemotely() = runTest {
        val router = ScopedToolRouter(mapOf(
            "camera.capture" to { ToolResult(true, "captured", verification = VerificationState.VERIFIED) },
            "microphone.record" to { ToolResult(true, "recorded", verification = VerificationState.VERIFIED) },
            "screen.capture" to { ToolResult(true, "captured", verification = VerificationState.VERIFIED) },
        ))
        val owner = ScopeRegistry().sessionFor(Principal("owner", "+14155550123", PrincipalRole.OWNER), "TELEGRAM")

        listOf("camera.capture", "microphone.record", "screen.capture").forEach { tool ->
            assertTrue(router.execute(owner, ToolCall(tool)).success)
        }
    }

    @Test
    fun explicitForegroundVoiceAndCaptureSurfacesCanExecuteSensorTools() = runTest {
        val handler: suspend (ToolCall) -> ToolResult<Any> = {
            ToolResult(true, "captured", verification = VerificationState.VERIFIED)
        }
        val router = ScopedToolRouter(mapOf("camera.capture" to handler))
        val scopes = ScopeRegistry()
        val owner = Principal("owner", "+14155550123", PrincipalRole.OWNER)

        listOf("FOREGROUND", "VOICE", "CAPTURE", "local").forEach { channel ->
            val result = router.execute(scopes.sessionFor(owner, channel), ToolCall("camera.capture"))

            assertTrue(result.success, "$channel should permit an explicitly initiated capture")
        }
    }

    @Test
    fun unknownCannotCallLocationEvenWhenPlannerRequestsIt() = runTest {
        val calls = mutableListOf<String>()
        val handler: suspend (ToolCall) -> ToolResult<Any> = { _: ToolCall -> calls += "location.current"; ToolResult(true, "San Francisco", verification = VerificationState.VERIFIED) }
        val router = ScopedToolRouter(mapOf("location.current" to handler))
        val unknown = ScopeRegistry().sessionFor(Principal("unknown", null, PrincipalRole.UNKNOWN), "sms")

        val result = router.execute(unknown, ToolCall("location.current"))

        assertEquals(ToolError.SCOPE_DENIED, result.error)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun coworkerGetsAvailabilityButNotPrivateCalendarDetails() {
        val registry = ScopeRegistry()
        val coworker = registry.sessionFor(Principal("alice", "+14155550100", PrincipalRole.KNOWN), "sms")
        registry.grant(coworker.principalId, ResourceType.TOOL, "calendar.availability")
        registry.grant(coworker.principalId, ResourceType.MEMORY, "coworker_shared")
        val context = ScopedContextBuilder(registry, mapOf("coworker_shared" to listOf("Available after 2"), "owner_private" to listOf("Private meeting"))).build(coworker)

        assertTrue("calendar.availability" in context.resources)
        assertFalse(context.memory.values.flatten().any { it.contains("Private meeting") })
    }

    @Test
    fun everyRouterDeniesResourcesOutsideScope() = runTest {
        val registry = ScopeRegistry()
        val unknown = registry.sessionFor(Principal("unknown", null, PrincipalRole.UNKNOWN), "sms")

        assertEquals(ToolError.SCOPE_DENIED, ScopedMcpRouter(registry, setOf("personal_email")).call(unknown, "personal_email").error)
        assertTrue(ScopedMemoryProvider(registry, mapOf("owner_private" to listOf("secret"))).read(unknown, "owner_private").isEmpty())
        assertTrue(ScopedSkillRegistry(registry, setOf("control-home")).availableFor(unknown).isEmpty())
    }
}
