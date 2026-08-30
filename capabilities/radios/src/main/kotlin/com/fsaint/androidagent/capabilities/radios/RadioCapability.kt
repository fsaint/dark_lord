package com.fsaint.androidagent.capabilities.radios

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

data class RadioTool(override val id: String) : AgentTool
enum class RadioPermission { GRANTED, DENIED }
enum class BluetoothState { ENABLED, DISABLED, UNAVAILABLE }
enum class WifiState { ENABLED, DISABLED, UNAVAILABLE }

data class BluetoothDeviceDescription(val address: String, val name: String?, val connected: Boolean)
data class WifiStatus(val enabled: Boolean, val connected: Boolean, val ssid: String?)

sealed interface BluetoothDevicesOutcome {
    data class Success(val devices: List<BluetoothDeviceDescription>) : BluetoothDevicesOutcome
    data object PermissionRequired : BluetoothDevicesOutcome
    data object Unsupported : BluetoothDevicesOutcome
    data object Disabled : BluetoothDevicesOutcome
}

enum class RadioOperationOutcome { Success, PermissionRequired, Unsupported, Disabled, DeviceBusy, Failed }

interface RadioAdapter {
    fun supported(): Boolean
    fun permission(): RadioPermission
    fun bluetoothState(): BluetoothState
    fun wifiState(): WifiState
    fun wifiStatus(): WifiStatus
    suspend fun bluetoothDevices(): BluetoothDevicesOutcome
    fun enableBluetooth(): RadioOperationOutcome
}

class RadioCapability(private val adapter: RadioAdapter) : AgentCapability {
    override val id = "radios"
    override val version = "1.0"

    override suspend fun initialize(): CapabilityStatus = status()
    override fun tools(): List<AgentTool> = RADIO_TOOLS.map(::RadioTool)
    override fun events(): Flow<AgentEvent> = emptyFlow()
    override fun status(): CapabilityStatus = CapabilityStatus(
        available = adapter.supported() && adapter.permission() == RadioPermission.GRANTED,
        details = mapOf(
            "supported" to adapter.supported().toString(),
            "permission" to adapter.permission().name.lowercase(),
            "bluetoothEnabled" to (adapter.bluetoothState() == BluetoothState.ENABLED).toString(),
            "wifiEnabled" to (adapter.wifiState() == WifiState.ENABLED).toString(),
        ),
    )

    suspend fun bluetoothStatus(): ToolResult<BluetoothState> = when {
        !adapter.supported() -> unsupported()
        adapter.permission() != RadioPermission.GRANTED -> permissionRequired()
        adapter.bluetoothState() == BluetoothState.UNAVAILABLE -> unsupported()
        adapter.bluetoothState() == BluetoothState.DISABLED -> permissionRequired()
        else -> ToolResult(true, adapter.bluetoothState(), verification = VerificationState.VERIFIED)
    }

    suspend fun bluetoothDevices(): ToolResult<List<BluetoothDeviceDescription>> {
        bluetoothStatus().let { if (!it.success) return it.mapPayload() }
        return when (val outcome = adapter.bluetoothDevices()) {
            is BluetoothDevicesOutcome.Success -> ToolResult(true, outcome.devices, verification = VerificationState.VERIFIED)
            BluetoothDevicesOutcome.PermissionRequired -> permissionRequired()
            BluetoothDevicesOutcome.Unsupported -> unsupported()
            BluetoothDevicesOutcome.Disabled -> permissionRequired()
        }
    }

    suspend fun wifiStatus(): ToolResult<WifiStatus> = when {
        !adapter.supported() -> unsupported()
        adapter.wifiState() == WifiState.UNAVAILABLE -> unsupported()
        adapter.wifiState() == WifiState.DISABLED -> permissionRequired()
        else -> ToolResult(true, adapter.wifiStatus(), verification = VerificationState.VERIFIED)
    }

    fun toolHandlers(): Map<String, suspend (ToolCall) -> ToolResult<Any>> = mapOf(
        "bluetooth.status" to { bluetoothStatus().toAnyResult() },
        "bluetooth.devices" to { bluetoothDevices().toAnyResult() },
        "bluetooth.enable" to { unsupported<Any>() },
        "wifi.status" to { wifiStatus().toAnyResult() },
        "wifi.scan" to { unsupported<Any>() },
        "wifi.connect" to { unsupported<Any>() },
    )
}

private val RADIO_TOOLS = listOf(
    "bluetooth.status", "bluetooth.devices", "bluetooth.enable", "wifi.status", "wifi.scan", "wifi.connect",
)

private fun <T> unsupported() = ToolResult<T>(false, error = ToolError.UNSUPPORTED)
private fun <T> permissionRequired() = ToolResult<T>(false, error = ToolError.PERMISSION_REQUIRED, recoverable = true)
private fun <T> ToolResult<*>.mapPayload(): ToolResult<T> = ToolResult(success, error = error, recoverable = recoverable, verification = verification)
private fun <T> ToolResult<T>.toAnyResult() = ToolResult<Any>(success, payload as Any?, error, recoverable, verification)
