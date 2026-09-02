package com.fsaint.androidagent

import com.fsaint.androidagent.capabilities.audio.MicrophoneStartRequest
import com.fsaint.androidagent.capabilities.camera.VideoClip
import com.fsaint.androidagent.capabilities.camera.VideoStartRequest
import com.fsaint.androidagent.model.ToolCall
import com.fsaint.androidagent.model.ToolError
import com.fsaint.androidagent.model.ToolResult

/**
 * Lease-aware implementations for media tools that are invoked directly rather than as jobs.
 * A long-running direct start holds the process-wide lease until its matching stop succeeds;
 * a bounded direct recording releases it when the call returns.
 */
internal class DirectMediaControlHandlers(
    private val lease: MediaResourceLease,
    private val onStartVideo: suspend (VideoStartRequest) -> ToolResult<Unit>,
    private val onStopVideo: suspend () -> ToolResult<VideoClip>,
    private val onStartMicrophone: suspend (MicrophoneStartRequest) -> ToolResult<Unit>,
    private val onStopMicrophone: suspend () -> ToolResult<*>,
    private val onRecordMicrophone: suspend (ToolCall) -> ToolResult<Any>,
) {
    fun handlers(): Map<String, suspend (ToolCall) -> ToolResult<Any>> = mapOf(
        "camera.startVideo" to ::startVideo,
        "camera.stopVideo" to { stopVideo() },
        "microphone.start" to ::startMicrophone,
        "microphone.stop" to { stopMicrophone() },
        "microphone.record" to ::recordMicrophone,
    )

    private suspend fun startVideo(call: ToolCall): ToolResult<Any> =
        acquireThenStart(VIDEO_OWNER) {
            onStartVideo(
                VideoStartRequest(
                    cameraId = call.arguments["cameraId"]?.takeIf(String::isNotBlank),
                    maxWidth = call.arguments["maxWidth"]?.toIntOrNull() ?: 1280,
                    maxHeight = call.arguments["maxHeight"]?.toIntOrNull() ?: 720,
                    maxDurationMs = call.arguments["maxDurationMs"]?.toLongOrNull() ?: 60_000,
                    maxBytes = call.arguments["maxBytes"]?.toIntOrNull() ?: 16_000_000,
                ),
            ).toAnyResult()
        }

    private suspend fun stopVideo(): ToolResult<Any> =
        stopIfOwner(VIDEO_OWNER) { onStopVideo().toAnyResult() }

    private suspend fun startMicrophone(call: ToolCall): ToolResult<Any> =
        acquireThenStart(MICROPHONE_OWNER) {
            onStartMicrophone(
                MicrophoneStartRequest(
                    sampleRateHz = call.arguments["sampleRateHz"]?.toIntOrNull() ?: 16_000,
                    maxBytes = call.arguments["maxBytes"]?.toIntOrNull() ?: 320_000,
                ),
            ).toAnyResult()
        }

    private suspend fun stopMicrophone(): ToolResult<Any> =
        stopIfOwner(MICROPHONE_OWNER) { onStopMicrophone().toAnyResult() }

    private suspend fun recordMicrophone(call: ToolCall): ToolResult<Any> {
        if (!lease.tryAcquire(RECORDING_OWNER)) return deviceBusy()
        return try {
            onRecordMicrophone(call)
        } finally {
            lease.release(RECORDING_OWNER)
        }
    }

    private suspend fun acquireThenStart(owner: String, block: suspend () -> ToolResult<Any>): ToolResult<Any> {
        if (!lease.tryAcquire(owner)) return deviceBusy()
        return try {
            block().also { if (!it.success) lease.release(owner) }
        } catch (t: Throwable) {
            lease.release(owner)
            throw t
        }
    }

    private suspend fun stopIfOwner(owner: String, block: suspend () -> ToolResult<Any>): ToolResult<Any> {
        if (lease.isOwner(owner)) return block().also { if (it.success) lease.release(owner) }

        val probeOwner = "$owner:stop"
        if (!lease.tryAcquire(probeOwner)) return deviceBusy()
        return try {
            block()
        } finally {
            lease.release(probeOwner)
        }
    }

    private fun deviceBusy(): ToolResult<Any> = ToolResult(false, error = ToolError.DEVICE_BUSY)

    private fun ToolResult<*>.toAnyResult(): ToolResult<Any> = ToolResult(
        success = success,
        payload = payload,
        error = error,
        recoverable = recoverable,
        verification = verification,
    )

    private companion object {
        const val VIDEO_OWNER = "direct:video"
        const val MICROPHONE_OWNER = "direct:microphone"
        const val RECORDING_OWNER = "direct:microphone-record"
    }
}
