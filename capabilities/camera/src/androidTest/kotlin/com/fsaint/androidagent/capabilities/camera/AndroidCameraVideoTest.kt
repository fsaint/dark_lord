package com.fsaint.androidagent.capabilities.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidCameraVideoTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
    )

    @Test
    fun recordsShortPlayableMp4AndDeletesItAfterVerification() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY))
        val adapter = AndroidCameraAdapter(context)

        assertEquals(
            CameraOperationOutcome.Success,
            adapter.startVideo(
                VideoStartRequest(
                    maxWidth = 1280,
                    maxHeight = 720,
                    maxDurationMs = 5_000,
                    maxBytes = 4_000_000,
                ),
            ),
        )
        delay(1_000)

        val result = adapter.stopVideo()
        assertTrue(result is CameraVideoStopOutcome.Success)
        val clip = (result as CameraVideoStopOutcome.Success).clip
        try {
            assertTrue(clip.file.exists())
            assertTrue(clip.file.length() > 0)
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(clip.file.absolutePath)
                assertTrue(
                    (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) > 0L,
                )
            }
        } finally {
            clip.file.delete()
        }
    }
}
