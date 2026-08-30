package com.fsaint.androidagent.capabilities.accessibility

import com.fsaint.androidagent.model.ToolCall
import com.fsaint.androidagent.model.ToolError
import com.fsaint.androidagent.model.VerificationState
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccessibilityCapabilityTest {
    @Test
    fun statusReportsEnabledAndConnectedState() = runTest {
        val capability = AccessibilityCapability(
            FakeAccessibilityAdapter(AccessibilityServiceState(enabled = true, connected = true)),
        )

        val result = capability.readStatus()

        assertTrue(result.success)
        assertEquals(AccessibilityServiceState(enabled = true, connected = true), result.payload)
        assertEquals(VerificationState.VERIFIED, result.verification)
        assertTrue(capability.status().available)
    }

    @Test
    fun inspectReportsPermissionRequiredWhenServiceIsDisabled() = runTest {
        val capability = AccessibilityCapability(
            FakeAccessibilityAdapter(
                state = AccessibilityServiceState(enabled = false, connected = false),
                inspectOutcome = AccessibilityInspectOutcome.PermissionRequired,
            ),
        )

        val result = capability.inspect(viewIdTarget())

        assertFalse(result.success)
        assertEquals(ToolError.PERMISSION_REQUIRED, result.error)
        assertTrue(result.recoverable)
    }

    @Test
    fun inspectReportsUnavailableWhenEnabledServiceIsNotConnected() = runTest {
        val capability = AccessibilityCapability(
            FakeAccessibilityAdapter(
                state = AccessibilityServiceState(enabled = true, connected = false),
                inspectOutcome = AccessibilityInspectOutcome.ServiceUnavailable,
            ),
        )

        val result = capability.inspect(viewIdTarget())

        assertFalse(result.success)
        assertEquals(ToolError.APP_NOT_RUNNING, result.error)
        assertTrue(result.recoverable)
    }

    @Test
    fun inspectRejectsTargetWithoutExplicitSelectorBeforeAdapterAccess() = runTest {
        val adapter = FakeAccessibilityAdapter(AccessibilityServiceState(true, true))
        val capability = AccessibilityCapability(adapter)

        val result = capability.inspect(AccessibilityTarget(packageName = "com.example.clock"))

        assertFalse(result.success)
        assertEquals(ToolError.SCOPE_DENIED, result.error)
        assertEquals(0, adapter.inspectCalls)
    }

    @Test
    fun inspectReturnsOneVerifiedExplicitMatch() = runTest {
        val expected = AccessibilityNodeSnapshot(
            packageName = "com.example.clock",
            className = "android.widget.Button",
            viewId = "com.example.clock:id/start",
            text = "Start",
            contentDescription = "Start timer",
            clickable = true,
            enabled = true,
        )
        val capability = AccessibilityCapability(
            FakeAccessibilityAdapter(
                state = AccessibilityServiceState(true, true),
                inspectOutcome = AccessibilityInspectOutcome.Success(expected),
            ),
        )

        val result = capability.inspect(viewIdTarget())

        assertTrue(result.success)
        assertEquals(expected, result.payload)
        assertEquals(VerificationState.VERIFIED, result.verification)
    }

    @Test
    fun actionRejectsUnaddressedTargetBeforeAdapterAccess() = runTest {
        val adapter = FakeAccessibilityAdapter(AccessibilityServiceState(true, true))
        val capability = AccessibilityCapability(adapter)
        val request = AccessibilityActionRequest(
            target = AccessibilityTarget(packageName = "com.example.clock"),
            action = AccessibilityAction.CLICK,
        )

        val result = capability.perform(request)

        assertFalse(result.success)
        assertEquals(ToolError.SCOPE_DENIED, result.error)
        assertEquals(0, adapter.actionCalls)
    }

    @Test
    fun actionReportsPlatformRejection() = runTest {
        val capability = AccessibilityCapability(
            FakeAccessibilityAdapter(
                state = AccessibilityServiceState(true, true),
                actionOutcome = AccessibilityActionOutcome.Rejected,
            ),
        )

        val result = capability.perform(
            AccessibilityActionRequest(viewIdTarget(), AccessibilityAction.CLICK),
        )

        assertFalse(result.success)
        assertEquals(ToolError.OS_RESTRICTED, result.error)
    }

    @Test
    fun setTextRejectsMissingValueBeforeAdapterAccess() = runTest {
        val adapter = FakeAccessibilityAdapter(
            state = AccessibilityServiceState(true, true),
            actionOutcome = AccessibilityActionOutcome.Performed,
        )
        val capability = AccessibilityCapability(adapter)

        val result = capability.perform(
            AccessibilityActionRequest(viewIdTarget(), AccessibilityAction.SET_TEXT),
        )

        assertFalse(result.success)
        assertEquals(ToolError.SCOPE_DENIED, result.error)
        assertEquals(0, adapter.actionCalls)
    }

    @Test
    fun handlersExposeStatusInspectAndActionContracts() = runTest {
        val snapshot = AccessibilityNodeSnapshot(
            packageName = "com.example.clock",
            className = "android.widget.Button",
            viewId = "com.example.clock:id/start",
            text = "Start",
            contentDescription = null,
            clickable = true,
            enabled = true,
        )
        val capability = AccessibilityCapability(
            FakeAccessibilityAdapter(
                state = AccessibilityServiceState(true, true),
                inspectOutcome = AccessibilityInspectOutcome.Success(snapshot),
                actionOutcome = AccessibilityActionOutcome.Performed,
            ),
        )
        val handlers = capability.toolHandlers()

        assertEquals(
            setOf("accessibility.status", "accessibility.inspect", "accessibility.action"),
            handlers.keys,
        )
        assertTrue(handlers.getValue("accessibility.status")(ToolCall("accessibility.status")).success)
        assertTrue(
            handlers.getValue("accessibility.inspect")(
                ToolCall(
                    "accessibility.inspect",
                    mapOf(
                        "packageName" to "com.example.clock",
                        "viewId" to "com.example.clock:id/start",
                    ),
                ),
            ).success,
        )
        assertTrue(
            handlers.getValue("accessibility.action")(
                ToolCall(
                    "accessibility.action",
                    mapOf(
                        "packageName" to "com.example.clock",
                        "viewId" to "com.example.clock:id/start",
                        "action" to "CLICK",
                    ),
                ),
            ).success,
        )
    }

    private fun viewIdTarget() = AccessibilityTarget(
        packageName = "com.example.clock",
        viewId = "com.example.clock:id/start",
    )
}

private class FakeAccessibilityAdapter(
    private val state: AccessibilityServiceState,
    private val inspectOutcome: AccessibilityInspectOutcome = AccessibilityInspectOutcome.NotFound,
    private val actionOutcome: AccessibilityActionOutcome = AccessibilityActionOutcome.NotFound,
) : AccessibilityAdapter {
    var inspectCalls = 0
        private set
    var actionCalls = 0
        private set

    override fun status(): AccessibilityServiceState = state

    override suspend fun inspect(target: AccessibilityTarget): AccessibilityInspectOutcome {
        inspectCalls += 1
        return inspectOutcome
    }

    override suspend fun perform(request: AccessibilityActionRequest): AccessibilityActionOutcome {
        actionCalls += 1
        return actionOutcome
    }
}
