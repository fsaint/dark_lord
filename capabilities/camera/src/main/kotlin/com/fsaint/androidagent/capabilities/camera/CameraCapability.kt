package com.fsaint.androidagent.capabilities.camera

import com.fsaint.androidagent.model.AgentCapability
import com.fsaint.androidagent.model.AgentEvent
import com.fsaint.androidagent.model.AgentTool
import com.fsaint.androidagent.model.CapabilityStatus
import com.fsaint.androidagent.model.ToolCall
import com.fsaint.androidagent.model.ToolError
import com.fsaint.androidagent.model.ToolResult
import com.fsaint.androidagent.model.VerificationState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File

data class CameraTool(override val id: String) : AgentTool

enum class CameraPermission { GRANTED, DENIED }
enum class CameraLensFacing { FRONT, BACK, EXTERNAL, UNKNOWN }

data class CameraDescription(
    val id: String,
    val lensFacing: CameraLensFacing,
    val flashAvailable: Boolean,
)

data class CameraCaptureRequest(
    val cameraId: String? = null,
    val maxWidth: Int = 1920,
    val maxHeight: Int = 1080,
    val maxBytes: Int = 4_000_000,
)

data class VideoStartRequest(
    val cameraId: String? = null,
    val maxWidth: Int = 1280,
    val maxHeight: Int = 720,
    val maxDurationMs: Long = 60_000,
    val maxBytes: Int = 16_000_000,
)

data class VideoClip(
    val file: File,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val durationMs: Long,
)

data class CameraImage(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val mimeType: String,
    val rotationDegrees: Int,
) {
    override fun equals(other: Any?): Boolean = other is CameraImage &&
        bytes.contentEquals(other.bytes) &&
        width == other.width &&
        height == other.height &&
        mimeType == other.mimeType &&
        rotationDegrees == other.rotationDegrees

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + width
}

sealed interface CameraListOutcome {
    data class Success(val cameras: List<CameraDescription>) : CameraListOutcome
    data object PermissionRequired : CameraListOutcome
    data object Unsupported : CameraListOutcome
}

sealed interface CameraCaptureOutcome {
    data class Success(val image: CameraImage) : CameraCaptureOutcome
    data object PermissionRequired : CameraCaptureOutcome
    data object Unsupported : CameraCaptureOutcome
    data object DeviceBusy : CameraCaptureOutcome
    data object NotFound : CameraCaptureOutcome
    data object TimedOut : CameraCaptureOutcome
    data object OsRestricted : CameraCaptureOutcome
    data object Failed : CameraCaptureOutcome
}

sealed interface CameraVideoStopOutcome {
    data class Success(val clip: VideoClip) : CameraVideoStopOutcome
    data object PermissionRequired : CameraVideoStopOutcome
    data object Unsupported : CameraVideoStopOutcome
    data object DeviceBusy : CameraVideoStopOutcome
    data object NotFound : CameraVideoStopOutcome
    data object OsRestricted : CameraVideoStopOutcome
    data object Failed : CameraVideoStopOutcome
}

enum class CameraOperationOutcome {
    Success,
    PermissionRequired,
    Unsupported,
    DeviceBusy,
    NotFound,
    OsRestricted,
    Failed,
}

interface CameraAdapter {
    fun permission(): CameraPermission
    fun supported(): Boolean
    suspend fun list(): CameraListOutcome
    suspend fun capture(request: CameraCaptureRequest): CameraCaptureOutcome
    suspend fun setTorch(cameraId: String, enabled: Boolean): CameraOperationOutcome
    suspend fun startVideo(request: VideoStartRequest): CameraOperationOutcome
    suspend fun stopVideo(): CameraVideoStopOutcome
    fun recordingVideo(): Boolean
}

class CameraCapability(private val adapter: CameraAdapter) : AgentCapability {
    override val id = "camera"
    override val version = "1.0"

    override suspend fun initialize(): CapabilityStatus = status()

    override fun tools(): List<AgentTool> = CAMERA_TOOLS.map(::CameraTool)

    override fun events(): Flow<AgentEvent> = emptyFlow()

    override fun status(): CapabilityStatus = CapabilityStatus(
        available = adapter.supported() && adapter.permission() == CameraPermission.GRANTED,
        details = mapOf(
            "supported" to adapter.supported().toString(),
            "permission" to adapter.permission().name.lowercase(),
        ),
    )

    suspend fun list(): ToolResult<List<CameraDescription>> {
        preflight<List<CameraDescription>>()?.let { return it }
        return when (val outcome = adapter.list()) {
            is CameraListOutcome.Success -> ToolResult(
                success = true,
                payload = outcome.cameras,
                verification = VerificationState.VERIFIED,
            )
            CameraListOutcome.PermissionRequired -> permissionRequired()
            CameraListOutcome.Unsupported -> unsupported()
        }
    }

    suspend fun capture(request: CameraCaptureRequest): ToolResult<CameraImage> {
        if (!request.isWithinHardBounds()) return ToolResult(success = false, error = ToolError.SCOPE_DENIED)
        preflight<CameraImage>()?.let { return it }
        return when (val outcome = adapter.capture(request)) {
            is CameraCaptureOutcome.Success -> if (outcome.image.isWithin(request)) {
                ToolResult(
                    success = true,
                    payload = outcome.image,
                    verification = VerificationState.VERIFIED,
                )
            } else {
                ToolResult(success = false, error = ToolError.OS_RESTRICTED)
            }
            CameraCaptureOutcome.PermissionRequired -> permissionRequired()
            CameraCaptureOutcome.Unsupported -> unsupported()
            CameraCaptureOutcome.DeviceBusy -> deviceBusy()
            CameraCaptureOutcome.NotFound -> ToolResult(success = false, error = ToolError.NOT_FOUND)
            CameraCaptureOutcome.TimedOut -> ToolResult(
                success = false,
                error = ToolError.TIMEOUT,
                recoverable = true,
            )
            CameraCaptureOutcome.OsRestricted,
            CameraCaptureOutcome.Failed,
            -> ToolResult(success = false, error = ToolError.OS_RESTRICTED, recoverable = true)
        }
    }

    suspend fun setTorch(cameraId: String, enabled: Boolean): ToolResult<Unit> {
        if (cameraId.isBlank()) return ToolResult(success = false, error = ToolError.SCOPE_DENIED)
        preflight<Unit>()?.let { return it }
        return adapter.setTorch(cameraId, enabled).toToolResult()
    }

    suspend fun startVideo(request: VideoStartRequest): ToolResult<Unit> {
        if (!request.isWithinHardBounds()) return ToolResult(success = false, error = ToolError.SCOPE_DENIED)
        preflight<Unit>()?.let { return it }
        return adapter.startVideo(request).toToolResult()
    }

    suspend fun stopVideo(): ToolResult<VideoClip> {
        preflight<VideoClip>()?.let { return it }
        return when (val outcome = adapter.stopVideo()) {
            is CameraVideoStopOutcome.Success -> ToolResult(
                success = true,
                payload = outcome.clip,
                verification = VerificationState.VERIFIED,
            )
            CameraVideoStopOutcome.PermissionRequired -> permissionRequired()
            CameraVideoStopOutcome.Unsupported -> unsupported()
            CameraVideoStopOutcome.DeviceBusy -> deviceBusy()
            CameraVideoStopOutcome.NotFound -> ToolResult(success = false, error = ToolError.NOT_FOUND)
            CameraVideoStopOutcome.OsRestricted,
            CameraVideoStopOutcome.Failed,
            -> ToolResult(success = false, error = ToolError.OS_RESTRICTED, recoverable = true)
        }
    }

    fun toolHandlers(): Map<String, suspend (ToolCall) -> ToolResult<Any>> = mapOf(
        "camera.list" to { list().toAnyResult() },
        "camera.capture" to { call ->
            capture(
                CameraCaptureRequest(
                    cameraId = call.arguments["cameraId"]?.takeIf(String::isNotBlank),
                    maxWidth = call.arguments["maxWidth"]?.toIntOrNull() ?: 1920,
                    maxHeight = call.arguments["maxHeight"]?.toIntOrNull() ?: 1080,
                    maxBytes = call.arguments["maxBytes"]?.toIntOrNull() ?: 4_000_000,
                ),
            ).toAnyResult()
        },
        "camera.startPreview" to { unsupported<Any>() },
        "camera.stopPreview" to { unsupported<Any>() },
        "camera.startVideo" to { call ->
            startVideo(
                VideoStartRequest(
                    cameraId = call.arguments["cameraId"]?.takeIf(String::isNotBlank),
                    maxWidth = call.arguments["maxWidth"]?.toIntOrNull() ?: 1280,
                    maxHeight = call.arguments["maxHeight"]?.toIntOrNull() ?: 720,
                    maxDurationMs = call.arguments["maxDurationMs"]?.toLongOrNull() ?: 60_000,
                    maxBytes = call.arguments["maxBytes"]?.toIntOrNull() ?: 16_000_000,
                ),
            ).toAnyResult()
        },
        "camera.stopVideo" to { stopVideo().toAnyResult() },
        "camera.setZoom" to { unsupported<Any>() },
        "camera.setFocus" to { unsupported<Any>() },
        "camera.setTorch" to { call ->
            val enabled = call.arguments["enabled"]?.toBooleanStrictOrNull()
                ?: return@to ToolResult(success = false, error = ToolError.SCOPE_DENIED)
            setTorch(call.arguments["cameraId"].orEmpty(), enabled).toAnyResult()
        },
    )

    private fun <T> preflight(): ToolResult<T>? = when {
        !adapter.supported() -> unsupported()
        adapter.permission() != CameraPermission.GRANTED -> permissionRequired()
        else -> null
    }
}

private val CAMERA_TOOLS = listOf(
    "camera.list",
    "camera.capture",
    "camera.startPreview",
    "camera.stopPreview",
    "camera.startVideo",
    "camera.stopVideo",
    "camera.setZoom",
    "camera.setFocus",
    "camera.setTorch",
)

private fun CameraCaptureRequest.isWithinHardBounds(): Boolean =
    maxWidth in 1..4096 && maxHeight in 1..4096 && maxBytes in 1..8_000_000

private fun VideoStartRequest.isWithinHardBounds(): Boolean =
    maxWidth in 1..4096 &&
        maxHeight in 1..4096 &&
        maxDurationMs in 1..600_000 &&
        maxBytes in 1..64_000_000

private fun CameraImage.isWithin(request: CameraCaptureRequest): Boolean =
    width in 1..request.maxWidth &&
        height in 1..request.maxHeight &&
        bytes.size in 3..request.maxBytes &&
        mimeType == "image/jpeg" &&
        bytes[0] == 0xFF.toByte() &&
        bytes[1] == 0xD8.toByte() &&
        bytes[2] == 0xFF.toByte() &&
        rotationDegrees in setOf(0, 90, 180, 270)

private fun CameraOperationOutcome.toToolResult(): ToolResult<Unit> = when (this) {
    CameraOperationOutcome.Success -> ToolResult(success = true, payload = Unit, verification = VerificationState.VERIFIED)
    CameraOperationOutcome.PermissionRequired -> permissionRequired()
    CameraOperationOutcome.Unsupported -> unsupported()
    CameraOperationOutcome.DeviceBusy -> deviceBusy()
    CameraOperationOutcome.NotFound -> ToolResult(success = false, error = ToolError.NOT_FOUND)
    CameraOperationOutcome.OsRestricted,
    CameraOperationOutcome.Failed,
    -> ToolResult(success = false, error = ToolError.OS_RESTRICTED, recoverable = true)
}

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

private fun <T> ToolResult<T>.toAnyResult(): ToolResult<Any> = ToolResult(
    success = success,
    payload = payload as Any?,
    error = error,
    recoverable = recoverable,
    verification = verification,
)
