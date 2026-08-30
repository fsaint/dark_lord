package com.fsaint.androidagent.capabilities.screen

import com.fsaint.androidagent.model.ToolCall
import com.fsaint.androidagent.model.ToolError
import com.fsaint.androidagent.model.VerificationState
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenCapabilityTest {
    @Test
    fun captureWithoutExplicitGrantRequiresUserPermission() = runTest {
        val result = ScreenCapability(FakeScreenCaptureAdapter(ScreenGrantState.NOT_GRANTED)).capture()

        assertFalse(result.success)
        assertEquals(ToolError.PERMISSION_REQUIRED, result.error)
        assertTrue(result.recoverable)
    }

    @Test
    fun captureBlockedBySecureWindowReportsSecureWindow() = runTest {
        val result = ScreenCapability(
            FakeScreenCaptureAdapter(
                grantState = ScreenGrantState.GRANTED,
                outcome = ScreenCaptureOutcome.SecureWindow,
            ),
        ).capture()

        assertFalse(result.success)
        assertEquals(ToolError.SECURE_WINDOW, result.error)
        assertFalse(result.recoverable)
    }

    @Test
    fun successfulCaptureReturnsBoundedVerifiedImage() = runTest {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val adapter = FakeScreenCaptureAdapter(
            grantState = ScreenGrantState.GRANTED,
            outcome = ScreenCaptureOutcome.Success(
                ScreenCaptureResult(
                    bytes = jpeg,
                    width = 720,
                    height = 1280,
                    mimeType = "image/jpeg",
                ),
            ),
        )
        val request = ScreenCaptureRequest(maxWidth = 720, maxHeight = 1280, maxBytes = 1_000_000)

        val result = ScreenCapability(adapter).capture(request)

        assertTrue(result.success)
        assertEquals(720, result.payload?.width)
        assertEquals(1280, result.payload?.height)
        assertEquals("image/jpeg", result.payload?.mimeType)
        assertContentEquals(jpeg, result.payload?.bytes)
        assertEquals(request, adapter.lastRequest)
        assertEquals(VerificationState.VERIFIED, result.verification)
    }

    @Test
    fun captureRejectsRequestsOutsideHardBoundsBeforeCallingAndroid() = runTest {
        val adapter = FakeScreenCaptureAdapter(ScreenGrantState.GRANTED)

        val result = ScreenCapability(adapter).capture(
            ScreenCaptureRequest(maxWidth = 10_000, maxHeight = 10_000, maxBytes = 100_000_000),
        )

        assertFalse(result.success)
        assertEquals(ToolError.SCOPE_DENIED, result.error)
        assertEquals(null, adapter.lastRequest)
    }

    @Test
    fun captureRejectsAdapterOutputThatExceedsRequestedBounds() = runTest {
        val adapter = FakeScreenCaptureAdapter(
            grantState = ScreenGrantState.GRANTED,
            outcome = ScreenCaptureOutcome.Success(
                ScreenCaptureResult(
                    bytes = ByteArray(2_001),
                    width = 101,
                    height = 201,
                    mimeType = "image/jpeg",
                ),
            ),
        )

        val result = ScreenCapability(adapter).capture(
            ScreenCaptureRequest(maxWidth = 100, maxHeight = 200, maxBytes = 2_000),
        )

        assertFalse(result.success)
        assertEquals(ToolError.OS_RESTRICTED, result.error)
        assertEquals(null, result.payload)
    }

    @Test
    fun captureRejectsAdapterOutputThatIsNotJpeg() = runTest {
        val adapter = FakeScreenCaptureAdapter(
            grantState = ScreenGrantState.GRANTED,
            outcome = ScreenCaptureOutcome.Success(
                ScreenCaptureResult(
                    bytes = byteArrayOf(0x01, 0x02, 0x03),
                    width = 50,
                    height = 60,
                    mimeType = "image/png",
                ),
            ),
        )

        val result = ScreenCapability(adapter).capture()

        assertFalse(result.success)
        assertEquals(ToolError.OS_RESTRICTED, result.error)
    }

    @Test
    fun unsupportedCaptureMapsToUnsupported() = runTest {
        val result = ScreenCapability(
            FakeScreenCaptureAdapter(
                grantState = ScreenGrantState.GRANTED,
                outcome = ScreenCaptureOutcome.Unsupported,
            ),
        ).capture()

        assertFalse(result.success)
        assertEquals(ToolError.UNSUPPORTED, result.error)
    }

    @Test
    fun handlerParsesBoundedCaptureRequest() = runTest {
        val adapter = FakeScreenCaptureAdapter(
            grantState = ScreenGrantState.GRANTED,
            outcome = ScreenCaptureOutcome.Success(
                ScreenCaptureResult(
                    byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()),
                    640,
                    960,
                    "image/jpeg",
                ),
            ),
        )
        val handler = ScreenCapability(adapter).toolHandlers().getValue("screen.capture")

        val result = handler(
            ToolCall(
                name = "screen.capture",
                arguments = mapOf("maxWidth" to "640", "maxHeight" to "960", "maxBytes" to "500000"),
            ),
        )

        assertTrue(result.success)
        assertEquals(ScreenCaptureRequest(640, 960, 500_000), adapter.lastRequest)
    }
}

private class FakeScreenCaptureAdapter(
    private val grantState: ScreenGrantState,
    private val outcome: ScreenCaptureOutcome = ScreenCaptureOutcome.PermissionRequired,
) : ScreenCaptureAdapter {
    var lastRequest: ScreenCaptureRequest? = null

    override fun grantState(): ScreenGrantState = grantState

    override suspend fun capture(request: ScreenCaptureRequest): ScreenCaptureOutcome {
        lastRequest = request
        return outcome
    }
}
