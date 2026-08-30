package com.fsaint.androidagent.capabilities.audio

import com.fsaint.androidagent.model.AgentCapability
import com.fsaint.androidagent.model.AgentEvent
import com.fsaint.androidagent.model.AgentTool
import com.fsaint.androidagent.model.CapabilityStatus
import com.fsaint.androidagent.model.ToolCall
import com.fsaint.androidagent.model.ToolError
import com.fsaint.androidagent.model.ToolResult
import com.fsaint.androidagent.model.VerificationState
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext

data class MediaTool(override val id: String) : AgentTool

enum class MicrophonePermission { GRANTED, DENIED }

data class MicrophoneRecordRequest(
    val durationMs: Long,
    val sampleRateHz: Int = 16_000,
    val maxBytes: Int = 320_000,
)

data class MicrophoneStartRequest(
    val sampleRateHz: Int = 16_000,
    val maxBytes: Int = 320_000,
)

data class MicrophoneClip(
    val bytes: ByteArray,
    val sampleRateHz: Int,
    val channelCount: Int,
    val encoding: String,
) {
    override fun equals(other: Any?): Boolean = other is MicrophoneClip &&
        bytes.contentEquals(other.bytes) &&
        sampleRateHz == other.sampleRateHz &&
        channelCount == other.channelCount &&
        encoding == other.encoding

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + sampleRateHz
}

data class MicrophoneLevel(val rmsDb: Float)

sealed interface MicrophoneRecordOutcome {
    data class Success(val clip: MicrophoneClip) : MicrophoneRecordOutcome
    data object PermissionRequired : MicrophoneRecordOutcome
    data object Unsupported : MicrophoneRecordOutcome
    data object DeviceBusy : MicrophoneRecordOutcome
    data object TimedOut : MicrophoneRecordOutcome
    data object OsRestricted : MicrophoneRecordOutcome
    data object Failed : MicrophoneRecordOutcome
}

enum class MicrophoneOperationOutcome {
    Success,
    PermissionRequired,
    Unsupported,
    DeviceBusy,
    OsRestricted,
    Failed,
}

sealed interface MicrophoneStopOutcome {
    data class Success(val clip: MicrophoneClip) : MicrophoneStopOutcome
    data object NotRecording : MicrophoneStopOutcome
    data object Failed : MicrophoneStopOutcome
}

sealed interface MicrophoneLevelOutcome {
    data class Success(val level: MicrophoneLevel) : MicrophoneLevelOutcome
    data object NotRecording : MicrophoneLevelOutcome
    data object Unsupported : MicrophoneLevelOutcome
}

interface MicrophoneAdapter {
    fun permission(): MicrophonePermission
    fun supported(): Boolean
    fun recording(): Boolean
    suspend fun record(request: MicrophoneRecordRequest): MicrophoneRecordOutcome {
        return when (
            val started = start(
                MicrophoneStartRequest(
                    sampleRateHz = request.sampleRateHz,
                    maxBytes = request.maxBytes,
                ),
            )
        ) {
            MicrophoneOperationOutcome.Success -> try {
                delay(request.durationMs)
                stop().toRecordOutcome()
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { stop() }
                throw cancelled
            }
            MicrophoneOperationOutcome.PermissionRequired -> MicrophoneRecordOutcome.PermissionRequired
            MicrophoneOperationOutcome.Unsupported -> MicrophoneRecordOutcome.Unsupported
            MicrophoneOperationOutcome.DeviceBusy -> MicrophoneRecordOutcome.DeviceBusy
            MicrophoneOperationOutcome.OsRestricted -> MicrophoneRecordOutcome.OsRestricted
            MicrophoneOperationOutcome.Failed -> MicrophoneRecordOutcome.Failed
        }
    }
    suspend fun start(request: MicrophoneStartRequest): MicrophoneOperationOutcome
    suspend fun stop(): MicrophoneStopOutcome
    fun level(): MicrophoneLevelOutcome
}

class MicrophoneCapability(private val adapter: MicrophoneAdapter) : AgentCapability {
    override val id = "microphone"
    override val version = "1.0"

    override suspend fun initialize(): CapabilityStatus = status()
    override fun tools(): List<AgentTool> = MICROPHONE_TOOLS.map(::MediaTool)
    override fun events(): Flow<AgentEvent> = emptyFlow()
    override fun status(): CapabilityStatus = CapabilityStatus(
        available = adapter.supported() && adapter.permission() == MicrophonePermission.GRANTED,
        details = mapOf(
            "supported" to adapter.supported().toString(),
            "permission" to adapter.permission().name.lowercase(),
            "recording" to adapter.recording().toString(),
        ),
    )

    suspend fun record(request: MicrophoneRecordRequest): ToolResult<MicrophoneClip> {
        if (!request.isWithinBounds()) return ToolResult(success = false, error = ToolError.SCOPE_DENIED)
        preflight<MicrophoneClip>()?.let { return it }
        return when (val outcome = adapter.record(request)) {
            is MicrophoneRecordOutcome.Success -> if (outcome.clip.isWithin(request)) {
                ToolResult(success = true, payload = outcome.clip, verification = VerificationState.VERIFIED)
            } else {
                ToolResult(success = false, error = ToolError.OS_RESTRICTED)
            }
            MicrophoneRecordOutcome.PermissionRequired -> permissionRequired()
            MicrophoneRecordOutcome.Unsupported -> unsupported()
            MicrophoneRecordOutcome.DeviceBusy -> deviceBusy()
            MicrophoneRecordOutcome.TimedOut -> ToolResult(success = false, error = ToolError.TIMEOUT, recoverable = true)
            MicrophoneRecordOutcome.OsRestricted,
            MicrophoneRecordOutcome.Failed,
            -> ToolResult(success = false, error = ToolError.OS_RESTRICTED, recoverable = true)
        }
    }

    suspend fun start(request: MicrophoneStartRequest): ToolResult<Unit> {
        if (!request.isWithinBounds()) return ToolResult(success = false, error = ToolError.SCOPE_DENIED)
        preflight<Unit>()?.let { return it }
        return adapter.start(request).toToolResult()
    }

    suspend fun stop(): ToolResult<MicrophoneClip> = when (val outcome = adapter.stop()) {
        is MicrophoneStopOutcome.Success -> ToolResult(
            success = true,
            payload = outcome.clip,
            verification = VerificationState.VERIFIED,
        )
        MicrophoneStopOutcome.NotRecording -> ToolResult(success = false, error = ToolError.APP_NOT_RUNNING)
        MicrophoneStopOutcome.Failed -> ToolResult(success = false, error = ToolError.OS_RESTRICTED, recoverable = true)
    }

    fun level(): ToolResult<MicrophoneLevel> = when (val outcome = adapter.level()) {
        is MicrophoneLevelOutcome.Success -> ToolResult(
            success = true,
            payload = outcome.level,
            verification = VerificationState.VERIFIED,
        )
        MicrophoneLevelOutcome.NotRecording -> ToolResult(success = false, error = ToolError.APP_NOT_RUNNING)
        MicrophoneLevelOutcome.Unsupported -> unsupported()
    }

    fun toolHandlers(): Map<String, suspend (ToolCall) -> ToolResult<Any>> = mapOf(
        "microphone.record" to { call ->
            record(
                MicrophoneRecordRequest(
                    durationMs = call.arguments["durationMs"]?.toLongOrNull() ?: 3_000,
                    sampleRateHz = call.arguments["sampleRateHz"]?.toIntOrNull() ?: 16_000,
                    maxBytes = call.arguments["maxBytes"]?.toIntOrNull() ?: 320_000,
                ),
            ).toAnyResult()
        },
        "microphone.start" to { call ->
            start(
                MicrophoneStartRequest(
                    sampleRateHz = call.arguments["sampleRateHz"]?.toIntOrNull() ?: 16_000,
                    maxBytes = call.arguments["maxBytes"]?.toIntOrNull() ?: 320_000,
                ),
            ).toAnyResult()
        },
        "microphone.stop" to { stop().toAnyResult() },
        "microphone.stream" to { unsupported<Any>() },
        "microphone.level" to { level().toAnyResult() },
    )

    private fun <T> preflight(): ToolResult<T>? = when {
        !adapter.supported() -> unsupported()
        adapter.permission() != MicrophonePermission.GRANTED -> permissionRequired()
        else -> null
    }
}

data class AudioOutputDevice(val id: Int, val type: String, val name: String)
data class AudioVolume(val normalizedLevel: Float)
data class AudioPlayRequest(
    val pcmS16Le: ByteArray,
    val sampleRateHz: Int,
    val channelCount: Int = 1,
)

sealed interface AudioVolumeOutcome {
    data class Success(val normalizedLevel: Float) : AudioVolumeOutcome
    data object Unsupported : AudioVolumeOutcome
    data object Failed : AudioVolumeOutcome
}

sealed interface AudioDevicesOutcome {
    data class Success(val devices: List<AudioOutputDevice>) : AudioDevicesOutcome
    data object Unsupported : AudioDevicesOutcome
    data object Failed : AudioDevicesOutcome
}

enum class AudioOperationOutcome {
    Success,
    Unsupported,
    DeviceBusy,
    NotFound,
    OsRestricted,
    Failed,
}

interface AudioAdapter {
    fun supported(): Boolean
    fun playing(): Boolean
    fun volume(): AudioVolumeOutcome
    fun setVolume(level: Float): AudioOperationOutcome
    fun outputDevices(): AudioDevicesOutcome
    suspend fun play(request: AudioPlayRequest): AudioOperationOutcome
    fun stop(): AudioOperationOutcome
    fun setOutputDevice(deviceId: Int): AudioOperationOutcome
}

class AudioCapability(private val adapter: AudioAdapter) : AgentCapability {
    override val id = "audio"
    override val version = "1.0"

    override suspend fun initialize(): CapabilityStatus = status()
    override fun tools(): List<AgentTool> = AUDIO_TOOLS.map(::MediaTool)
    override fun events(): Flow<AgentEvent> = emptyFlow()
    override fun status(): CapabilityStatus = CapabilityStatus(
        available = adapter.supported(),
        details = mapOf("playing" to adapter.playing().toString()),
    )

    fun volume(): ToolResult<AudioVolume> {
        if (!adapter.supported()) return unsupported()
        return when (val outcome = adapter.volume()) {
            is AudioVolumeOutcome.Success -> ToolResult(
                success = true,
                payload = AudioVolume(outcome.normalizedLevel),
                verification = VerificationState.VERIFIED,
            )
            AudioVolumeOutcome.Unsupported -> unsupported()
            AudioVolumeOutcome.Failed -> osRestricted()
        }
    }

    fun setVolume(level: Float): ToolResult<Unit> {
        if (level !in 0f..1f) return ToolResult(success = false, error = ToolError.SCOPE_DENIED)
        if (!adapter.supported()) return unsupported()
        return adapter.setVolume(level).toToolResult()
    }

    fun outputDevices(): ToolResult<List<AudioOutputDevice>> {
        if (!adapter.supported()) return unsupported()
        return when (val outcome = adapter.outputDevices()) {
            is AudioDevicesOutcome.Success -> ToolResult(
                success = true,
                payload = outcome.devices,
                verification = VerificationState.VERIFIED,
            )
            AudioDevicesOutcome.Unsupported -> unsupported()
            AudioDevicesOutcome.Failed -> osRestricted()
        }
    }

    suspend fun play(request: AudioPlayRequest): ToolResult<Unit> {
        if (!request.isWithinBounds()) return ToolResult(success = false, error = ToolError.SCOPE_DENIED)
        if (!adapter.supported()) return unsupported()
        return adapter.play(request).toToolResult()
    }

    fun stop(): ToolResult<Unit> = if (!adapter.supported()) unsupported() else adapter.stop().toToolResult()

    fun setOutputDevice(deviceId: Int): ToolResult<Unit> = when {
        deviceId < 0 -> ToolResult(success = false, error = ToolError.SCOPE_DENIED)
        !adapter.supported() -> unsupported()
        else -> adapter.setOutputDevice(deviceId).toToolResult()
    }

    fun tts(text: String): ToolResult<Unit> = if (text.length > 2_000) {
        ToolResult(success = false, error = ToolError.SCOPE_DENIED)
    } else {
        unsupported()
    }

    fun toolHandlers(): Map<String, suspend (ToolCall) -> ToolResult<Any>> = mapOf(
        "audio.play" to { call ->
            val bytes = call.arguments["pcmBase64"]?.decodeBase64()
                ?: return@to ToolResult(success = false, error = ToolError.SCOPE_DENIED)
            play(
                AudioPlayRequest(
                    pcmS16Le = bytes,
                    sampleRateHz = call.arguments["sampleRateHz"]?.toIntOrNull() ?: 16_000,
                    channelCount = call.arguments["channelCount"]?.toIntOrNull() ?: 1,
                ),
            ).toAnyResult()
        },
        "audio.stop" to { stop().toAnyResult() },
        "audio.tts" to { call -> tts(call.arguments["text"].orEmpty()).toAnyResult() },
        "audio.volume" to { call ->
            call.arguments["level"]?.toFloatOrNull()?.let(::setVolume)?.toAnyResult()
                ?: volume().toAnyResult()
        },
        "audio.outputDevices" to { outputDevices().toAnyResult() },
        "audio.setOutputDevice" to { call ->
            val deviceId = call.arguments["deviceId"]?.toIntOrNull()
                ?: return@to ToolResult(success = false, error = ToolError.SCOPE_DENIED)
            setOutputDevice(deviceId).toAnyResult()
        },
    )
}

private val MICROPHONE_TOOLS = listOf(
    "microphone.record",
    "microphone.start",
    "microphone.stop",
    "microphone.stream",
    "microphone.level",
)

private val AUDIO_TOOLS = listOf(
    "audio.play",
    "audio.stop",
    "audio.tts",
    "audio.volume",
    "audio.outputDevices",
    "audio.setOutputDevice",
)

private fun MicrophoneRecordRequest.isWithinBounds(): Boolean =
    durationMs in 100..10_000 && sampleRateHz in setOf(8_000, 16_000, 44_100, 48_000) && maxBytes in 1..1_000_000

private fun MicrophoneStartRequest.isWithinBounds(): Boolean =
    sampleRateHz in setOf(8_000, 16_000, 44_100, 48_000) && maxBytes in 1..1_000_000

private fun MicrophoneClip.isWithin(request: MicrophoneRecordRequest): Boolean =
    bytes.isNotEmpty() &&
        bytes.size <= request.maxBytes &&
        sampleRateHz == request.sampleRateHz &&
        channelCount == 1 &&
        encoding == "pcm_s16le"

private fun AudioPlayRequest.isWithinBounds(): Boolean =
    pcmS16Le.isNotEmpty() &&
        pcmS16Le.size <= 1_000_000 &&
        pcmS16Le.size % 2 == 0 &&
        sampleRateHz in setOf(8_000, 16_000, 44_100, 48_000) &&
        channelCount in 1..2

private fun MicrophoneOperationOutcome.toToolResult(): ToolResult<Unit> = when (this) {
    MicrophoneOperationOutcome.Success -> verifiedUnit()
    MicrophoneOperationOutcome.PermissionRequired -> permissionRequired()
    MicrophoneOperationOutcome.Unsupported -> unsupported()
    MicrophoneOperationOutcome.DeviceBusy -> deviceBusy()
    MicrophoneOperationOutcome.OsRestricted,
    MicrophoneOperationOutcome.Failed,
    -> osRestricted()
}

private fun MicrophoneStopOutcome.toRecordOutcome(): MicrophoneRecordOutcome = when (this) {
    is MicrophoneStopOutcome.Success -> MicrophoneRecordOutcome.Success(clip)
    MicrophoneStopOutcome.NotRecording,
    MicrophoneStopOutcome.Failed,
    -> MicrophoneRecordOutcome.Failed
}

private fun AudioOperationOutcome.toToolResult(): ToolResult<Unit> = when (this) {
    AudioOperationOutcome.Success -> verifiedUnit()
    AudioOperationOutcome.Unsupported -> unsupported()
    AudioOperationOutcome.DeviceBusy -> deviceBusy()
    AudioOperationOutcome.NotFound -> ToolResult(success = false, error = ToolError.NOT_FOUND)
    AudioOperationOutcome.OsRestricted,
    AudioOperationOutcome.Failed,
    -> osRestricted()
}

private fun verifiedUnit(): ToolResult<Unit> = ToolResult(
    success = true,
    payload = Unit,
    verification = VerificationState.VERIFIED,
)

private fun <T> permissionRequired(): ToolResult<T> = ToolResult(
    success = false,
    error = ToolError.PERMISSION_REQUIRED,
    recoverable = true,
)

private fun <T> unsupported(): ToolResult<T> = ToolResult(success = false, error = ToolError.UNSUPPORTED)

private fun <T> deviceBusy(): ToolResult<T> = ToolResult(
    success = false,
    error = ToolError.DEVICE_BUSY,
    recoverable = true,
)

private fun <T> osRestricted(): ToolResult<T> = ToolResult(
    success = false,
    error = ToolError.OS_RESTRICTED,
    recoverable = true,
)

private fun String.decodeBase64(): ByteArray? = try {
    Base64.getDecoder().decode(this)
} catch (_: IllegalArgumentException) {
    null
}

private fun <T> ToolResult<T>.toAnyResult(): ToolResult<Any> = ToolResult(
    success = success,
    payload = payload as Any?,
    error = error,
    recoverable = recoverable,
    verification = verification,
)
