package com.fsaint.androidagent.capabilities.radios

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidRadioAdapterConnectedTest {
    @Test
    fun statusReflectsRealDeviceWithoutChangingRadioState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val adapter = AndroidRadioAdapter(context)
        val status = RadioCapability(adapter).status()
        assertEquals(adapter.supported(), status.details["supported"] == "true")
        assertEquals(
            if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                RadioPermission.GRANTED
            } else {
                RadioPermission.DENIED
            },
            adapter.permission(),
        )
    }

    @Test
    fun appDeclaresBluetoothPermission() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
        )
        assertTrue(Manifest.permission.BLUETOOTH_CONNECT in info.requestedPermissions.orEmpty())
    }
}
