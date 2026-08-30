package com.fsaint.androidagent.runtime

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class BootCoordinatorTest {
    @Test
    fun restoresInPolicyOrderExactlyOnce() = runTest {
        val calls = mutableListOf<String>()
        val coordinator = BootCoordinator(listOf("scopes", "skills", "capabilities", "schedules", "mcp", "runtime").map { name ->
            RestoreStep(name) { calls += name; RestoreOutcome.Restored }
        })
        coordinator.restore()
        coordinator.restore()
        assertEquals(listOf("scopes", "skills", "capabilities", "schedules", "mcp", "runtime"), calls)
    }

    @Test
    fun failedStepIsReportedButLaterStepsStillRestore() = runTest {
        val calls = mutableListOf<String>()
        val coordinator = BootCoordinator(listOf(
            RestoreStep("scopes") { calls += "scopes"; RestoreOutcome.Failed(com.fsaint.androidagent.model.ToolError.OS_RESTRICTED) },
            RestoreStep("skills") { calls += "skills"; RestoreOutcome.Restored },
        ))
        val report = coordinator.restore()
        assertEquals(listOf("scopes", "skills"), calls)
        assertEquals(1, report.failures.size)
    }
}
