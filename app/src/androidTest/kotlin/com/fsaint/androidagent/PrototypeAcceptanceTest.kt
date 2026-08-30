package com.fsaint.androidagent

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fsaint.androidagent.capabilities.camera.CameraAdapter
import com.fsaint.androidagent.capabilities.camera.CameraCapability
import com.fsaint.androidagent.capabilities.camera.CameraCaptureOutcome
import com.fsaint.androidagent.capabilities.camera.CameraCaptureRequest
import com.fsaint.androidagent.capabilities.camera.CameraDescription
import com.fsaint.androidagent.capabilities.camera.CameraLensFacing
import com.fsaint.androidagent.capabilities.camera.CameraListOutcome
import com.fsaint.androidagent.capabilities.camera.CameraOperationOutcome
import com.fsaint.androidagent.capabilities.camera.CameraPermission
import com.fsaint.androidagent.diagnostics.DiagnosticsRepository
import com.fsaint.androidagent.ui.DebugScreen
import com.fsaint.androidagent.ui.OpenAssistantScreen
import com.fsaint.androidagent.model.PrincipalRole
import com.fsaint.androidagent.model.ScopedAgentSession
import com.fsaint.androidagent.model.ToolCall
import com.fsaint.androidagent.model.ToolError
import com.fsaint.androidagent.policy.ScopeRegistry
import com.fsaint.androidagent.policy.ScopedToolRouter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrototypeAcceptanceTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun manifestRegistersRuntimeAndAdministrationComponents() {
        val pm = context.packageManager
        val packageName = context.packageName
        assertNotNull(pm.getReceiverInfo(ComponentName(packageName, "${packageName}.BootReceiver"), PackageManager.GET_META_DATA))
        val admin = pm.getReceiverInfo(ComponentName(packageName, "${packageName}.AgentDeviceAdminReceiver"), PackageManager.GET_META_DATA)
        assertNotNull(admin.metaData)
        assertNotNull(pm.getServiceInfo(ComponentName(packageName, "com.fsaint.androidagent.oem.samsungflip3.AgentVoiceInteractionService"), 0))
        assertNotNull(pm.getServiceInfo(ComponentName(packageName, "com.fsaint.androidagent.communications.RespondViaMessageService"), 0))
    }

    @Test fun diagnosticsSurfaceIsReachableFromAssistantSurface() {
        var open = false
        compose.setContent {
            if (open) DebugScreen(DiagnosticsRepository(), onBack = {}) else OpenAssistantScreen(
                onRequestAssistantRole = {}, onRequestCapabilityPermissions = {}, onOpenDiagnostics = { open = true },
            )
        }
        compose.onNodeWithText("Diagnostics").performClick()
        compose.onNodeWithText("Read-only local health snapshot. Sensitive values are redacted.").assertIsDisplayed()
    }

    @Test fun ungrantedPrincipalCannotCallScopedTool() = runBlocking {
        val scopes = ScopeRegistry()
        val session = ScopedAgentSession("s", "known", PrincipalRole.KNOWN, "known", "test", "known", 0)
        val router = ScopedToolRouter(mapOf("device.status" to { _ -> ToolCallResult.success() }), scopes)
        assertEquals(ToolError.SCOPE_DENIED, router.execute(session, ToolCall("device.status")).error)
    }

    @Test fun cameraReportsPermissionRequiredInsteadOfPretendingAvailability() = runBlocking {
        val capability = CameraCapability(PermissionDeniedCameraAdapter())
        assertEquals(false, capability.status().available)
        assertEquals(ToolError.PERMISSION_REQUIRED, capability.list().error)
    }

    private object ToolCallResult {
        fun success() = com.fsaint.androidagent.model.ToolResult<Any>(true, Unit)
    }

    private class PermissionDeniedCameraAdapter : CameraAdapter {
        override fun permission() = CameraPermission.DENIED
        override fun supported() = true
        override suspend fun list(): CameraListOutcome = CameraListOutcome.PermissionRequired
        override suspend fun capture(request: CameraCaptureRequest): CameraCaptureOutcome = CameraCaptureOutcome.PermissionRequired
        override suspend fun setTorch(cameraId: String, enabled: Boolean) = CameraOperationOutcome.PermissionRequired
    }
}
