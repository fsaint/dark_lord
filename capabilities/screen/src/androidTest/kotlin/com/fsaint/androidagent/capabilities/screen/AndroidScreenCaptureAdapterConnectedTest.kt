package com.fsaint.androidagent.capabilities.screen

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidScreenCaptureAdapterConnectedTest {
    @Test
    fun canceledConsentRemainsPermissionRequiredWithoutStartingCapture() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val adapter = AndroidScreenCaptureAdapter(context)

        adapter.acceptGrant(Activity.RESULT_CANCELED, null)
        val result = adapter.capture(ScreenCaptureRequest())

        assertEquals(ScreenGrantState.NOT_GRANTED, adapter.grantState())
        assertEquals(ScreenCaptureOutcome.PermissionRequired, result)
    }

    @Test
    fun foregroundServiceIsDeclaredForMediaProjection() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val component = ComponentName(context, ScreenCaptureForegroundService::class.java)

        val service = context.packageManager.getServiceInfo(component, PackageManager.ComponentInfoFlags.of(0))
        val requestedPermissions = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
            .requestedPermissions
            ?.toSet()
            .orEmpty()

        assertFalse(service.exported)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION, service.foregroundServiceType)
        assertTrue(Manifest.permission.FOREGROUND_SERVICE in requestedPermissions)
        assertTrue(Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION in requestedPermissions)
    }

    @Test
    fun blackSecureWindowFixtureMapsToSecureWindow() {
        val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLACK)
        }

        val result = BoundedScreenImageEncoder.encode(
            bitmap,
            ScreenCaptureRequest(maxWidth = 32, maxHeight = 48, maxBytes = 64_000),
        )

        assertEquals(ScreenCaptureOutcome.SecureWindow, result)
    }

    @Test
    fun mostlyBlackSecureFixtureWithVisibleSystemStripMapsToSecureWindow() {
        val bitmap = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLACK)
            for (x in 0 until width) {
                for (y in 0 until 8) setPixel(x, y, Color.WHITE)
            }
        }

        val result = BoundedScreenImageEncoder.encode(
            bitmap,
            ScreenCaptureRequest(maxWidth = 100, maxHeight = 200, maxBytes = 64_000),
        )

        assertEquals(ScreenCaptureOutcome.SecureWindow, result)
    }

    @Test
    fun visibleFixtureProducesBoundedJpeg() {
        val bitmap = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(20, 40, 220))
            setPixel(0, 0, Color.WHITE)
        }

        val result = BoundedScreenImageEncoder.encode(
            bitmap,
            ScreenCaptureRequest(maxWidth = 50, maxHeight = 80, maxBytes = 10_000),
        )

        assertTrue(result is ScreenCaptureOutcome.Success)
        val capture = (result as ScreenCaptureOutcome.Success).capture
        assertTrue(capture.width <= 50)
        assertTrue(capture.height <= 80)
        assertTrue(capture.bytes.size <= 10_000)
        assertEquals("image/jpeg", capture.mimeType)
    }

    @Test(timeout = 2_000)
    fun impossibleByteLimitFailsWithoutLoopingOrReturningOversizedData() {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
            setPixel(0, 0, Color.WHITE)
        }

        val result = BoundedScreenImageEncoder.encode(
            bitmap,
            ScreenCaptureRequest(maxWidth = 2, maxHeight = 2, maxBytes = 1),
        )

        assertEquals(ScreenCaptureOutcome.Failed, result)
    }
}
