package com.fsaint.androidagent.capabilities.environment

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.hardware.usb.UsbManager
import android.location.LocationManager
import android.nfc.NfcAdapter
import android.provider.ContactsContract
import java.io.File

class AndroidEnvironmentAdapter(private val context: Context) : EnvironmentAdapter {
    override fun permissionGranted() = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    override fun supported() = true
    @Suppress("MissingPermission") // EnvironmentCapability checks the runtime permission before invoking this adapter.
    override fun locationStatus(): LocationStatus {
        val manager = context.getSystemService(LocationManager::class.java)
        val enabled = manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        val location = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).asSequence()
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }.firstOrNull()
        return LocationStatus(enabled, location?.latitude, location?.longitude)
    }
    override fun sensors() = context.getSystemService(SensorManager::class.java).getSensorList(android.hardware.Sensor.TYPE_ALL)
        .take(100).map { SensorDescription(it.type.toString(), it.name.take(120)) }
    override fun nfcStatus(): NfcStatus {
        val nfc = NfcAdapter.getDefaultAdapter(context)
        return NfcStatus(nfc != null, nfc?.isEnabled == true)
    }
    override fun usbStatus(): UsbStatus {
        val usb = context.getSystemService(UsbManager::class.java)
        return UsbStatus(true, usb.deviceList.size.coerceAtMost(100))
    }
    override suspend fun contacts(): List<ContactSummary> {
        val result = mutableListOf<ContactSummary>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val name = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val phone = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext() && result.size < 100) result += ContactSummary(cursor.getString(id), cursor.getString(name).orEmpty().take(120), cursor.getString(phone)?.take(40))
        }
        return result
    }
    override suspend fun files(): List<FileSummary> = context.filesDir.listFiles().orEmpty().take(100).map { FileSummary(it.name.take(120), it.length().coerceAtMost(50L * 1024 * 1024), it.isDirectory) }
}
