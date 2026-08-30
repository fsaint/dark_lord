package com.fsaint.androidagent.capabilities.audio

import com.fsaint.androidagent.model.ToolCall
import com.fsaint.androidagent.model.ToolError
import com.fsaint.androidagent.model.VerificationState
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaCapabilitiesTest {
    @Test
    fun microphonePermissionDenialIsRecoverable() = runTest {
        val capability = MicrophoneCapability(FakeMicrophoneAdapter(permission = MicrophonePermission.DENIED))

        val result = capability.record(MicrophoneRecordRequest(durationMs = 1_000))

        assertEquals(ToolError.PERMISSION_REQUIRED, result.error)
        assertTrue(result.recoverable)
    }

    @Test
    fun occupiedMicrophoneReturnsDeviceBusy() = runTest {
        val capability = MicrophoneCapability(FakeMicrophoneAdapter(recordOutcome = MicrophoneRecordOutcome.DeviceBusy))

        val result = capability.record(MicrophoneRecordRequest(durationMs = 1_000))

        assertEquals(ToolError.DEVICE_BUSY, result.error)
    }

    @Test
    fun boundedMicrophoneRecordingIsVerified() = runTest {
        val clip = MicrophoneClip(byteArrayOf(1, 2, 3), sampleRateHz = 16_000, channelCount = 1, encoding = "pcm_s16le")
        val capability = MicrophoneCapability(FakeMicrophoneAdapter(recordOutcome = MicrophoneRecordOutcome.Success(clip)))

        val result = capability.record(MicrophoneRecordRequest(durationMs = 1_000, maxBytes = 32_000))

        assertTrue(result.success)
        assertEquals(clip, result.payload)
        assertEquals(VerificationState.VERIFIED, result.verification)
    }

    @Test
    fun excessiveRecordingDurationIsScopeDeniedBeforeOpeningMicrophone() = runTest {
        val adapter = FakeMicrophoneAdapter()
        val capability = MicrophoneCapability(adapter)

        val result = capability.record(MicrophoneRecordRequest(durationMs = 60_000))

        assertEquals(ToolError.SCOPE_DENIED, result.error)
        assertEquals(0, adapter.recordCalls)
    }

    @Test
    fun cancellingFixedRecordingAlwaysReleasesMicrophoneSession() = runTest {
        val adapter = FakeMicrophoneAdapter(startOutcome = MicrophoneOperationOutcome.Success)
        val capability = MicrophoneCapability(adapter)
        val recording = launch {
            capability.record(MicrophoneRecordRequest(durationMs = 10_000))
        }
        yield()

        recording.cancel()
        recording.join()

        assertEquals(1, adapter.stopCalls)
    }

    @Test
    fun microphoneToolsAllRouteAndStreamIsExplicitlyUnsupported() = runTest {
        val capability = MicrophoneCapability(FakeMicrophoneAdapter())

        assertEquals(capability.tools().map { it.id }.toSet(), capability.toolHandlers().keys)
        assertEquals(
            ToolError.UNSUPPORTED,
            capability.toolHandlers().getValue("microphone.stream")(ToolCall("microphone.stream")).error,
        )
    }

    @Test
    fun unavailableSpeakerReturnsUnsupported() = runTest {
        val capability = AudioCapability(FakeAudioAdapter(supported = false))

        val result = capability.outputDevices()

        assertEquals(ToolError.UNSUPPORTED, result.error)
    }

    @Test
    fun volumeOutsideNormalizedRangeIsScopeDenied() = runTest {
        val capability = AudioCapability(FakeAudioAdapter())

        val result = capability.setVolume(1.5f)

        assertEquals(ToolError.SCOPE_DENIED, result.error)
    }

    @Test
    fun outputDevicesComeFromAdapterAndAreVerified() = runTest {
        val devices = listOf(AudioOutputDevice(7, "speaker", "Built-in speaker"))
        val capability = AudioCapability(FakeAudioAdapter(devices = devices))

        val result = capability.outputDevices()

        assertEquals(devices, result.payload)
        assertEquals(VerificationState.VERIFIED, result.verification)
    }

    @Test
    fun busyPlaybackReturnsDeviceBusy() = runTest {
        val capability = AudioCapability(FakeAudioAdapter(playOutcome = AudioOperationOutcome.DeviceBusy))

        val result = capability.play(AudioPlayRequest(byteArrayOf(1, 2), sampleRateHz = 16_000))

        assertEquals(ToolError.DEVICE_BUSY, result.error)
    }

    @Test
    fun audioToolsAllHaveHandlersAndTtsIsTruthfullyUnsupported() = runTest {
        val capability = AudioCapability(FakeAudioAdapter())

        assertEquals(capability.tools().map { it.id }.toSet(), capability.toolHandlers().keys)
        assertEquals(ToolError.UNSUPPORTED, capability.tts("hello").error)
    }
}

private class FakeMicrophoneAdapter(
    private val permission: MicrophonePermission = MicrophonePermission.GRANTED,
    private val supported: Boolean = true,
    private val recordOutcome: MicrophoneRecordOutcome = MicrophoneRecordOutcome.Unsupported,
    private val startOutcome: MicrophoneOperationOutcome = MicrophoneOperationOutcome.Unsupported,
) : MicrophoneAdapter {
    var recordCalls = 0
    var stopCalls = 0
    override fun permission(): MicrophonePermission = permission
    override fun supported(): Boolean = supported
    override fun recording(): Boolean = false
    override suspend fun record(request: MicrophoneRecordRequest): MicrophoneRecordOutcome {
        recordCalls += 1
        return if (recordOutcome == MicrophoneRecordOutcome.Unsupported && startOutcome != MicrophoneOperationOutcome.Unsupported) {
            super.record(request)
        } else {
            recordOutcome
        }
    }
    override suspend fun start(request: MicrophoneStartRequest): MicrophoneOperationOutcome = startOutcome
    override suspend fun stop(): MicrophoneStopOutcome {
        stopCalls += 1
        return MicrophoneStopOutcome.NotRecording
    }
    override fun level(): MicrophoneLevelOutcome = MicrophoneLevelOutcome.NotRecording
}

private class FakeAudioAdapter(
    private val supported: Boolean = true,
    private val devices: List<AudioOutputDevice> = emptyList(),
    private val playOutcome: AudioOperationOutcome = AudioOperationOutcome.Unsupported,
) : AudioAdapter {
    override fun supported(): Boolean = supported
    override fun playing(): Boolean = false
    override fun volume(): AudioVolumeOutcome = AudioVolumeOutcome.Success(0.5f)
    override fun setVolume(level: Float): AudioOperationOutcome = AudioOperationOutcome.Success
    override fun outputDevices(): AudioDevicesOutcome = AudioDevicesOutcome.Success(devices)
    override suspend fun play(request: AudioPlayRequest): AudioOperationOutcome = playOutcome
    override fun stop(): AudioOperationOutcome = AudioOperationOutcome.Success
    override fun setOutputDevice(deviceId: Int): AudioOperationOutcome = AudioOperationOutcome.Unsupported
}
