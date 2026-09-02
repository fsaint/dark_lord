package com.fsaint.androidagent.capabilities.camera

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoRecorderSessionTest {
    @Test
    fun recorderConfigurationUsesBoundedMp4H264AacProfile() {
        val configuration = VideoRecorderConfiguration.create(
            width = 1280,
            height = 720,
            maxDurationMs = 700_000,
            maxBytes = 2_000_000,
        )

        assertEquals(VideoRecorderConfiguration.MP4, configuration.outputFormat)
        assertEquals(VideoRecorderConfiguration.H264, configuration.videoEncoder)
        assertEquals(VideoRecorderConfiguration.AAC, configuration.audioEncoder)
        assertEquals(1280, configuration.width)
        assertEquals(720, configuration.height)
        assertEquals(600_000, configuration.maxDurationMs)
        assertEquals(2_000_000, configuration.maxBytes)
    }

    @Test
    fun finalizerRetainsOneValidLimitClipAcrossRepeatedFinalization() {
        val directory = Files.createTempDirectory("video-recorder-test").toFile()
        val file = directory.resolve("bounded.mp4").apply { writeBytes(ByteArray(64)) }
        val finalizer = VideoRecordingFinalizer(
            VideoRecorderConfiguration.create(1280, 720, maxDurationMs = 1_000, maxBytes = 128),
        )

        val first = finalizer.finalize(file, durationMs = 1_000)
        val second = finalizer.finalize(file, durationMs = 1_000)

        assertEquals(first, second)
        assertTrue(file.exists())
        directory.deleteRecursively()
    }

    @Test
    fun finalizerDeletesEmptyAndOutOfBoundsOutput() {
        val directory = Files.createTempDirectory("video-recorder-test").toFile()
        val finalizer = VideoRecordingFinalizer(
            VideoRecorderConfiguration.create(1280, 720, maxDurationMs = 1_000, maxBytes = 128),
        )
        val empty = directory.resolve("empty.mp4").apply { createNewFile() }
        val oversized = directory.resolve("oversized.mp4").apply { writeBytes(ByteArray(129)) }
        val tooLong = directory.resolve("too-long.mp4").apply { writeBytes(ByteArray(64)) }

        assertNull(finalizer.finalize(empty, durationMs = 1))
        assertNull(finalizer.finalize(oversized, durationMs = 1))
        assertNull(finalizer.finalize(tooLong, durationMs = 1_001))
        assertFalse(empty.exists())
        assertFalse(oversized.exists())
        assertFalse(tooLong.exists())
        directory.deleteRecursively()
    }

    @Test
    fun lateCameraOpenIsClosedAfterTimeout() = runTest {
        var closedCamera: String? = null
        val gate = VideoOpenGate<String> { closedCamera = it }

        gate.timeout()
        gate.opened("late-camera")

        assertEquals(VideoCameraOpen.Failure(CameraOperationOutcome.Failed), gate.await())
        assertEquals("late-camera", closedCamera)
    }
}
