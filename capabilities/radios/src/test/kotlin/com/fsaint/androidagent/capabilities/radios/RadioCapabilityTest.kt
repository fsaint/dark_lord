package com.fsaint.androidagent.capabilities.radios

import com.fsaint.androidagent.model.ToolError
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RadioCapabilityTest {
    @Test
    fun statusReportsBluetoothAndWifiState() = runTest {
        val result = RadioCapability(FakeRadioAdapter()).status()
        assertTrue(result.available)
        assertEquals("true", result.details["bluetoothEnabled"])
        assertEquals("true", result.details["wifiEnabled"])
    }

    @Test
    fun disabledBluetoothIsReportedWithoutTryingToEnableIt() = runTest {
        val adapter = FakeRadioAdapter(bluetooth = BluetoothState.DISABLED)
        val result = RadioCapability(adapter).bluetoothStatus()
        assertFalse(result.success)
        assertEquals(ToolError.PERMISSION_REQUIRED, result.error)
        assertFalse(adapter.enableCalled)
    }

    @Test
    fun bluetoothDevicesReturnKnownDevices() = runTest {
        val device = BluetoothDeviceDescription("AA:BB", "Headset", connected = true)
        val result = RadioCapability(FakeRadioAdapter(devices = listOf(device))).bluetoothDevices()
        assertTrue(result.success)
        assertEquals(listOf(device), result.payload)
    }

    @Test
    fun missingBluetoothPermissionIsStructured() = runTest {
        val result = RadioCapability(FakeRadioAdapter(permission = RadioPermission.DENIED)).bluetoothDevices()
        assertFalse(result.success)
        assertEquals(ToolError.PERMISSION_REQUIRED, result.error)
    }

    @Test
    fun wifiDisabledIsReported() = runTest {
        val result = RadioCapability(FakeRadioAdapter(wifi = WifiState.DISABLED)).wifiStatus()
        assertFalse(result.success)
        assertEquals(ToolError.PERMISSION_REQUIRED, result.error)
    }

    @Test
    fun unsupportedRadiosAreReported() = runTest {
        val result = RadioCapability(FakeRadioAdapter(supported = false)).wifiStatus()
        assertFalse(result.success)
        assertEquals(ToolError.UNSUPPORTED, result.error)
    }
}

private class FakeRadioAdapter(
    private val supported: Boolean = true,
    private val permission: RadioPermission = RadioPermission.GRANTED,
    private val bluetooth: BluetoothState = BluetoothState.ENABLED,
    private val wifi: WifiState = WifiState.ENABLED,
    private val devices: List<BluetoothDeviceDescription> = emptyList(),
) : RadioAdapter {
    var enableCalled = false

    override fun supported() = supported
    override fun permission() = permission
    override fun bluetoothState() = bluetooth
    override fun wifiState() = wifi
    override fun wifiStatus() = WifiStatus(enabled = wifi == WifiState.ENABLED, connected = false, ssid = null)
    override suspend fun bluetoothDevices() = BluetoothDevicesOutcome.Success(devices)
    override fun enableBluetooth(): RadioOperationOutcome {
        enableCalled = true
        return RadioOperationOutcome.Success
    }
}
