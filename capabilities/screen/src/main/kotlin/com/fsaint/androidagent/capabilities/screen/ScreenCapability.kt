package com.fsaint.androidagent.capabilities.screen

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

data class ScreenTool(override val id: String) : AgentTool

enum class ScreenGrantState {
    NOT_GRANTED,
    GRANTED,
}

data class ScreenCaptureRequest(
    val maxWidth: Int = 1080,
    val maxHeight: Int = 2400,
    val maxBytes: Int = 2_000_000,
)

data class ScreenCaptureResult(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val mimeType: String,
)

sealed interface ScreenCaptureOutcome {
    data class Success(val capture: ScreenCaptureResult) : ScreenCaptureOutcome
    data object PermissionRequired : ScreenCaptureOutcome
    data object SecureWindow : ScreenCaptureOutcome
    data object Unsupported : ScreenCaptureOutcome
    data object DeviceBusy : ScreenCaptureOutcome
    data object TimedOut : ScreenCaptureOutcome
    data object Failed : ScreenCaptureOutcome
}

interface ScreenCaptureAdapter {
    fun grantState(): ScreenGrantState
    suspend fun capture(request: ScreenCaptureRequest): ScreenCaptureOutcome
}

class ScreenCapability(private val adapter: ScreenCaptureAdapter) : AgentCapability {
    override val id = "screen"
    override val version = "1.0"

    override suspend fun initialize(): CapabilityStatus = status()

    override fun tools(): List<AgentTool> = listOf(ScreenTool("screen.capture"))

    override fun events(): Flow<AgentEvent> = emptyFlow()

    override fun status(): CapabilityStatus {
        val grant = adapter.grantState()
        return CapabilityStatus(
            available = grant == ScreenGrantState.GRANTED,
            details = mapOf("grant" to grant.name.lowercase()),
        )
    }

    suspend fun capture(request: ScreenCaptureRequest = ScreenCaptureRequest()): ToolResult<ScreenCaptureResult> {
        if (!request.isWithinHardBounds()) {
            return ToolResult(success = false, error = ToolError.SCOPE_DENIED)
        }
        if (adapter.grantState() != ScreenGrantState.GRANTED) {
            return ToolResult(
                success = false,
                error = ToolError.PERMISSION_REQUIRED,
                recoverable = true,
            )
        }

        return when (val outcome = adapter.capture(request)) {
            is ScreenCaptureOutcome.Success -> if (outcome.capture.isWithin(request)) {
                ToolResult(
                    success = true,
                    payload = outcome.capture,
                    verification = VerificationState.VERIFIED,
                )
            } else {
                ToolResult(success = false, error = ToolError.OS_RESTRICTED)
            }
            ScreenCaptureOutcome.PermissionRequired -> ToolResult(
                success = false,
                error = ToolError.PERMISSION_REQUIRED,
                recoverable = true,
            )
            ScreenCaptureOutcome.SecureWindow -> ToolResult(
                success = false,
                error = ToolError.SECURE_WINDOW,
            )
            ScreenCaptureOutcome.Unsupported -> ToolResult(success = false, error = ToolError.UNSUPPORTED)
            ScreenCaptureOutcome.DeviceBusy -> ToolResult(
                success = false,
                error = ToolError.DEVICE_BUSY,
                recoverable = true,
            )
            ScreenCaptureOutcome.TimedOut -> ToolResult(
                success = false,
                error = ToolError.TIMEOUT,
                recoverable = true,
            )
            ScreenCaptureOutcome.Failed -> ToolResult(
                success = false,
                error = ToolError.OS_RESTRICTED,
                recoverable = true,
            )
        }
    }

    fun toolHandlers(): Map<String, suspend (ToolCall) -> ToolResult<Any>> = mapOf(
        "screen.capture" to { call ->
            val request = ScreenCaptureRequest(
                maxWidth = call.arguments["maxWidth"]?.toIntOrNull() ?: 1080,
                maxHeight = call.arguments["maxHeight"]?.toIntOrNull() ?: 2400,
                maxBytes = call.arguments["maxBytes"]?.toIntOrNull() ?: 2_000_000,
            )
            capture(request).toAnyResult()
        },
    )
}

private fun ScreenCaptureRequest.isWithinHardBounds(): Boolean =
    maxWidth in 1..2160 &&
        maxHeight in 1..4320 &&
        maxBytes in 1..4_000_000

private fun ScreenCaptureResult.isWithin(request: ScreenCaptureRequest): Boolean =
    width in 1..request.maxWidth &&
        height in 1..request.maxHeight &&
        bytes.isNotEmpty() &&
        bytes.size <= request.maxBytes &&
        mimeType == "image/jpeg" &&
        bytes.size >= 3 &&
        bytes[0] == 0xFF.toByte() &&
        bytes[1] == 0xD8.toByte() &&
        bytes[2] == 0xFF.toByte()

private fun <T> ToolResult<T>.toAnyResult(): ToolResult<Any> = ToolResult(
    success = success,
    payload = payload as Any?,
    error = error,
    recoverable = recoverable,
    verification = verification,
)
