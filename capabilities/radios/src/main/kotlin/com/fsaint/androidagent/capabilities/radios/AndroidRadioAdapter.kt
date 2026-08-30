package com.fsaint.androidagent.capabilities.radios

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.annotation.SuppressLint

class AndroidRadioAdapter(private val context: Context) : RadioAdapter {
    private val bluetoothManager: BluetoothManager? by lazy { context.getSystemService(BluetoothManager::class.java) }
    private val wifiManager: WifiManager? by lazy { context.applicationContext.getSystemService(WifiManager::class.java) }

    override fun supported(): Boolean = bluetoothManager?.adapter != null || wifiManager != null

    override fun permission(): RadioPermission = if (
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    ) RadioPermission.GRANTED else RadioPermission.DENIED

    override fun bluetoothState(): BluetoothState = when (val adapter = bluetoothManager?.adapter) {
        null -> BluetoothState.UNAVAILABLE
        else -> if (adapter.isEnabled) BluetoothState.ENABLED else BluetoothState.DISABLED
    }

    override fun wifiState(): WifiState = when (val manager = wifiManager) {
        null -> WifiState.UNAVAILABLE
        else -> if (manager.isWifiEnabled) WifiState.ENABLED else WifiState.DISABLED
    }

    override fun wifiStatus(): WifiStatus {
        val manager = wifiManager ?: return WifiStatus(enabled = false, connected = false, ssid = null)
        val info = manager.connectionInfo
        return WifiStatus(
            enabled = manager.isWifiEnabled,
            connected = manager.isWifiEnabled && info.networkId != -1,
            // SSID is intentionally omitted: Android may redact it without location access.
            ssid = null,
        )
    }

    @SuppressLint("MissingPermission")
    override suspend fun bluetoothDevices(): BluetoothDevicesOutcome {
        if (permission() != RadioPermission.GRANTED) return BluetoothDevicesOutcome.PermissionRequired
        val adapter = bluetoothManager?.adapter ?: return BluetoothDevicesOutcome.Unsupported
        if (!adapter.isEnabled) return BluetoothDevicesOutcome.Disabled
        return runCatching {
            BluetoothDevicesOutcome.Success(
                adapter.bondedDevices.orEmpty().map { device ->
                    BluetoothDeviceDescription(device.address, device.name, connected = false)
                },
            )
        }.getOrElse { BluetoothDevicesOutcome.PermissionRequired }
    }

    override fun enableBluetooth(): RadioOperationOutcome =
        RadioOperationOutcome.PermissionRequired // Enabling radios is always user-mediated.
}
