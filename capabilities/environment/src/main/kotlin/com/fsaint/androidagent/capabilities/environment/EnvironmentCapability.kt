package com.fsaint.androidagent.capabilities.environment

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

data class EnvironmentTool(override val id: String) : AgentTool
data class LocationStatus(val enabled: Boolean, val latitude: Double?, val longitude: Double?)
data class SensorDescription(val type: String, val name: String)
data class NfcStatus(val supported: Boolean, val enabled: Boolean)
data class UsbStatus(val supported: Boolean, val deviceCount: Int)
data class ContactSummary(val id: String, val displayName: String, val phone: String?)
data class FileSummary(val name: String, val sizeBytes: Long, val directory: Boolean)

interface EnvironmentAdapter {
    fun permissionGranted(): Boolean
    fun supported(): Boolean
    fun locationStatus(): LocationStatus
    fun sensors(): List<SensorDescription>
    fun nfcStatus(): NfcStatus
    fun usbStatus(): UsbStatus
    suspend fun contacts(): List<ContactSummary>
    suspend fun files(): List<FileSummary>
}

class EnvironmentCapability(private val adapter: EnvironmentAdapter) : AgentCapability {
    override val id = "environment"
    override val version = "1.0"
    override suspend fun initialize(): CapabilityStatus = status()
    override fun status() = CapabilityStatus(
        available = adapter.supported() && adapter.permissionGranted(),
        details = mapOf("supported" to adapter.supported().toString(), "permission" to adapter.permissionGranted().toString()),
    )
    override fun tools(): List<AgentTool> = TOOL_IDS.map(::EnvironmentTool)
    override fun events(): Flow<AgentEvent> = emptyFlow()

    fun locationStatus() = result { adapter.locationStatus() }
    fun sensors() = result { adapter.sensors().take(MAX_ITEMS) }
    fun nfcStatus() = result { adapter.nfcStatus() }
    fun usbStatus() = result { adapter.usbStatus() }
    suspend fun contacts() = asyncResult { adapter.contacts().take(MAX_ITEMS) }
    suspend fun files() = asyncResult { adapter.files().take(MAX_ITEMS) }

    fun toolHandlers(): Map<String, suspend (ToolCall) -> ToolResult<Any>> = mapOf(
        "location.status" to { locationStatus().any() },
        "sensors.list" to { sensors().any() },
        "nfc.status" to { nfcStatus().any() },
        "usb.status" to { usbStatus().any() },
        "contacts.list" to { contacts().any() },
        "files.list" to { files().any() },
    )

    private fun <T> result(read: () -> T): ToolResult<T> = when {
        !adapter.supported() -> ToolResult(false, error = ToolError.UNSUPPORTED)
        !adapter.permissionGranted() -> ToolResult(false, error = ToolError.PERMISSION_REQUIRED, recoverable = true)
        else -> ToolResult(true, read(), verification = VerificationState.VERIFIED)
    }

    private companion object { const val MAX_ITEMS = 100; val TOOL_IDS = listOf("location.status", "sensors.list", "nfc.status", "usb.status", "contacts.list", "files.list") }
}

private suspend fun <T> EnvironmentCapability.asyncResult(read: suspend () -> T): ToolResult<T> = try {
    // Suspend adapters retain the same policy boundary as synchronous reads.
    if (!status().available) ToolResult(false, error = if (!status().details.getValue("supported").toBoolean()) ToolError.UNSUPPORTED else ToolError.PERMISSION_REQUIRED, recoverable = true)
    else ToolResult(true, read(), verification = VerificationState.VERIFIED)
} catch (_: SecurityException) { ToolResult(false, error = ToolError.PERMISSION_REQUIRED, recoverable = true) }

private fun <T> ToolResult<T>.any() = ToolResult<Any>(success, payload as Any?, error, recoverable, verification)
