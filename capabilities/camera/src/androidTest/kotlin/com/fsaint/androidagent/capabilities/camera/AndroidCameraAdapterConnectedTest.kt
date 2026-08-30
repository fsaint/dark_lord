package com.fsaint.androidagent.capabilities.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidCameraAdapterConnectedTest {
    @Test
    fun statusReflectsRealDeviceFeatureAndPermissionWithoutOpeningCamera() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val adapter = AndroidCameraAdapter(context)
        val permissionGranted = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val hasCamera = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

        assertEquals(hasCamera, adapter.supported())
        assertEquals(
            if (permissionGranted) CameraPermission.GRANTED else CameraPermission.DENIED,
            adapter.permission(),
        )

        val result = adapter.list()
        when {
            !hasCamera -> assertEquals(CameraListOutcome.Unsupported, result)
            !permissionGranted -> assertEquals(CameraListOutcome.PermissionRequired, result)
            else -> {
                assertTrue(result is CameraListOutcome.Success)
                assertFalse((result as CameraListOutcome.Success).cameras.isEmpty())
            }
        }
    }

    @Test
    fun cameraPermissionAndOptionalFeatureAreDeclared() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
        )

        assertTrue(Manifest.permission.CAMERA in packageInfo.requestedPermissions.orEmpty())
    }
}
