package com.fsaint.androidagent.capabilities.apps

import com.fsaint.androidagent.model.ToolCall
import com.fsaint.androidagent.model.ToolError
import com.fsaint.androidagent.model.VerificationState
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppsCapabilityTest {
    @Test
    fun listReturnsTypedInstalledAppRecords() = runTest {
        val capability = AppsCapability(
            FakeAppsAdapter(
                listOutcome = AppsListOutcome.Success(
                    listOf(
                        InstalledApp(label = "Clock", packageName = "com.example.clock", enabled = true),
                        InstalledApp(label = "Disabled", packageName = "com.example.disabled", enabled = false),
                    ),
                ),
            ),
        )

        val result = capability.list()

        assertTrue(result.success)
        assertEquals(
            listOf(
                InstalledApp("Clock", "com.example.clock", true),
                InstalledApp("Disabled", "com.example.disabled", false),
            ),
            result.payload,
        )
        assertEquals(VerificationState.VERIFIED, result.verification)
    }

    @Test
    fun listReportsPermissionRequiredTruthfully() = runTest {
        val result = AppsCapability(FakeAppsAdapter(listOutcome = AppsListOutcome.PermissionRequired)).list()

        assertFalse(result.success)
        assertEquals(ToolError.PERMISSION_REQUIRED, result.error)
        assertTrue(result.recoverable)
    }

    @Test
    fun listReportsUnsupportedTruthfully() = runTest {
        val result = AppsCapability(FakeAppsAdapter(listOutcome = AppsListOutcome.Unsupported)).list()

        assertFalse(result.success)
        assertEquals(ToolError.UNSUPPORTED, result.error)
        assertFalse(result.recoverable)
    }

    @Test
    fun launchReturnsVerifiedSuccessForLaunchablePackage() = runTest {
        val capability = AppsCapability(FakeAppsAdapter(launchOutcomes = mapOf("com.example.clock" to AppLaunchOutcome.Launched)))

        val result = capability.launch("com.example.clock")

        assertTrue(result.success)
        assertEquals("com.example.clock", result.payload?.packageName)
        assertEquals(VerificationState.VERIFIED, result.verification)
    }

    @Test
    fun launchReturnsNotFoundForUnknownPackage() = runTest {
        val capability = AppsCapability(FakeAppsAdapter(launchOutcomes = mapOf("missing" to AppLaunchOutcome.NotFound)))

        val result = capability.launch("missing")

        assertFalse(result.success)
        assertEquals(ToolError.NOT_FOUND, result.error)
        assertFalse(result.recoverable)
    }

    @Test
    fun launchReturnsAppNotRunningForPackageWithoutLaunchIntent() = runTest {
        val capability = AppsCapability(FakeAppsAdapter(launchOutcomes = mapOf("com.example.service" to AppLaunchOutcome.NotLaunchable)))

        val result = capability.launch("com.example.service")

        assertFalse(result.success)
        assertEquals(ToolError.APP_NOT_RUNNING, result.error)
        assertTrue(result.recoverable)
    }

    @Test
    fun handlersExposeAppsListAndAppsLaunch() = runTest {
        val capability = AppsCapability(
            FakeAppsAdapter(
                listOutcome = AppsListOutcome.Success(listOf(InstalledApp("Clock", "com.example.clock", true))),
                launchOutcomes = mapOf("com.example.clock" to AppLaunchOutcome.Launched),
            ),
        )
        val handlers = capability.toolHandlers()

        assertEquals(setOf("apps.list", "apps.launch"), handlers.keys)
        assertTrue(handlers.getValue("apps.list")(ToolCall("apps.list")).success)
        assertTrue(
            handlers.getValue("apps.launch")(
                ToolCall("apps.launch", mapOf("packageName" to "com.example.clock")),
            ).success,
        )
    }
}

private class FakeAppsAdapter(
    private val listOutcome: AppsListOutcome = AppsListOutcome.Success(emptyList()),
    private val launchOutcomes: Map<String, AppLaunchOutcome> = emptyMap(),
) : AppsAdapter {
    override suspend fun list(): AppsListOutcome = listOutcome

    override suspend fun launch(packageName: String): AppLaunchOutcome =
        launchOutcomes[packageName] ?: AppLaunchOutcome.NotFound
}
