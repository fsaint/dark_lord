package com.fsaint.androidagent.capabilities.camera

import com.fsaint.androidagent.model.ToolCall
import com.fsaint.androidagent.model.ToolError
import com.fsaint.androidagent.model.VerificationState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CameraCapabilityTest {
    @Test
    fun missingCameraPermissionIsRecoverableInsteadOfPretendingCaptureSucceeded() = runTest {
        val capability = CameraCapability(FakeCameraAdapter(permission = CameraPermission.DENIED))

        val result = capability.capture(CameraCaptureRequest())

        assertFalse(result.success)
        assertEquals(ToolError.PERMISSION_REQUIRED, result.error)
        assertTrue(result.recoverable)
    }

    @Test
    fun unavailableCameraReturnsUnsupported() = runTest {
        val capability = CameraCapability(FakeCameraAdapter(supported = false))

        val result = capability.list()

        assertEquals(ToolError.UNSUPPORTED, result.error)
    }

    @Test
    fun cameraInUseReturnsDeviceBusy() = runTest {
        val capability = CameraCapability(FakeCameraAdapter(captureOutcome = CameraCaptureOutcome.DeviceBusy))

        val result = capability.capture(CameraCaptureRequest())

        assertEquals(ToolError.DEVICE_BUSY, result.error)
        assertTrue(result.recoverable)
    }

    @Test
    fun successfulCaptureIsBoundedAndVerified() = runTest {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1)
        val image = CameraImage(jpeg, width = 640, height = 480, mimeType = "image/jpeg", rotationDegrees = 90)
        val capability = CameraCapability(FakeCameraAdapter(captureOutcome = CameraCaptureOutcome.Success(image)))

        val result = capability.capture(CameraCaptureRequest(maxWidth = 800, maxHeight = 600, maxBytes = 1_024))

        assertTrue(result.success)
        assertEquals(image, result.payload)
        assertEquals(VerificationState.VERIFIED, result.verification)
    }

    @Test
    fun oversizedAdapterResultIsRejected() = runTest {
        val image = CameraImage(
            bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1),
            width = 1200,
            height = 900,
            mimeType = "image/jpeg",
            rotationDegrees = 0,
        )
        val capability = CameraCapability(FakeCameraAdapter(captureOutcome = CameraCaptureOutcome.Success(image)))

        val result = capability.capture(CameraCaptureRequest(maxWidth = 640, maxHeight = 480, maxBytes = 1_024))

        assertEquals(ToolError.OS_RESTRICTED, result.error)
    }

    @Test
    fun captureHandlerRejectsInvalidHardBoundsWithoutCallingAndroid() = runTest {
        val adapter = FakeCameraAdapter()
        val handler = CameraCapability(adapter).toolHandlers().getValue("camera.capture")

        val result = handler(ToolCall("camera.capture", mapOf("maxBytes" to "0")))

        assertEquals(ToolError.SCOPE_DENIED, result.error)
        assertEquals(0, adapter.captureCalls)
    }

    @Test
    fun declaredCameraToolsHaveHandlersIncludingTruthfulUnsupportedSessionTools() {
        val capability = CameraCapability(FakeCameraAdapter())

        assertEquals(capability.tools().map { it.id }.toSet(), capability.toolHandlers().keys)
    }
}

private class FakeCameraAdapter(
    private val permission: CameraPermission = CameraPermission.GRANTED,
    private val supported: Boolean = true,
    private val captureOutcome: CameraCaptureOutcome = CameraCaptureOutcome.Unsupported,
) : CameraAdapter {
    var captureCalls = 0

    override fun permission(): CameraPermission = permission
    override fun supported(): Boolean = supported
    override suspend fun list(): CameraListOutcome = CameraListOutcome.Success(
        listOf(CameraDescription("0", CameraLensFacing.BACK, flashAvailable = true)),
    )

    override suspend fun capture(request: CameraCaptureRequest): CameraCaptureOutcome {
        captureCalls += 1
        return captureOutcome
    }

    override suspend fun setTorch(cameraId: String, enabled: Boolean): CameraOperationOutcome =
        CameraOperationOutcome.Unsupported
}
