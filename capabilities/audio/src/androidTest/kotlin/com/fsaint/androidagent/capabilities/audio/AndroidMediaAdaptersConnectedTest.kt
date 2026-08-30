package com.fsaint.androidagent.capabilities.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidMediaAdaptersConnectedTest {
    @Test
    fun microphoneStatusReflectsRealDeviceAndPermissionWithoutRecording() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val adapter = AndroidMicrophoneAdapter(context)
        val permissionGranted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val hasMicrophone = context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)

        assertEquals(hasMicrophone, adapter.supported())
        assertEquals(
            if (permissionGranted) MicrophonePermission.GRANTED else MicrophonePermission.DENIED,
            adapter.permission(),
        )
        assertFalse(adapter.recording())
    }

    @Test
    fun audioAdapterReportsRealOutputDevices() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val result = AndroidAudioAdapter(context).outputDevices()

        assertTrue(result is AudioDevicesOutcome.Success)
        assertFalse((result as AudioDevicesOutcome.Success).devices.isEmpty())
    }

    @Test
    fun recordAudioPermissionIsDeclared() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
        )

        assertTrue(Manifest.permission.RECORD_AUDIO in packageInfo.requestedPermissions.orEmpty())
    }
}
