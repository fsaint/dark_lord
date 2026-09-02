package com.fsaint.androidagent.capabilities.camera

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
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

    @Test
    fun durationLimitStopExceptionRetainsTheBoundedClip() {
        assertLimitStopExceptionRetainsBoundedClip(VideoRecorderLimit.DURATION)
    }

    @Test
    fun fileSizeLimitStopExceptionRetainsTheBoundedClip() {
        assertLimitStopExceptionRetainsBoundedClip(VideoRecorderLimit.FILE_SIZE)
    }

    @Test
    fun failedStopDeletesTheTemporaryOutput() {
        val directory = Files.createTempDirectory("video-recorder-test").toFile()
        val file = directory.resolve("failed.mp4").apply { writeBytes(ByteArray(64)) }
        val session = VideoRecorderSession.forRecordingTest(
            configuration = VideoRecorderConfiguration.create(1280, 720, 1_000, 128),
            file = file,
            recorder = ThrowingRecorderBackend(),
            clockMs = { 1_000L },
        )
        session.recordingStarted()

        assertFailsWith<RuntimeException> { session.stop() }
        assertFalse(file.exists())
        directory.deleteRecursively()
    }

    @Test
    fun abortedStartDeletesItsTemporaryOutput() {
        val directory = Files.createTempDirectory("video-recorder-test").toFile()
        val file = directory.resolve("unstarted.mp4").apply { createNewFile() }
        val session = VideoRecorderSession.forRecordingTest(
            configuration = VideoRecorderConfiguration.create(1280, 720, 1_000, 128),
            file = file,
            recorder = ThrowingRecorderBackend(),
            clockMs = { 0L },
        )

        session.abort()

        assertFalse(file.exists())
        directory.deleteRecursively()
    }

    private fun assertLimitStopExceptionRetainsBoundedClip(limit: VideoRecorderLimit) {
        val directory = Files.createTempDirectory("video-recorder-test").toFile()
        val file = directory.resolve("limited.mp4").apply { writeBytes(ByteArray(64)) }
        var nowMs = 0L
        val recorder = ThrowingRecorderBackend()
        val session = VideoRecorderSession.forRecordingTest(
            configuration = VideoRecorderConfiguration.create(1280, 720, 1_000, 128),
            file = file,
            recorder = recorder,
            clockMs = { nowMs },
        )
        session.recordingStarted()
        nowMs = 1_000L
        session.limitReached(limit)

        val first = session.stop()
        val second = session.stop()
        session.abort()

        assertEquals(first, second)
        assertEquals(1_000, first.durationMs)
        assertEquals(1, recorder.stopCalls)
        assertTrue(file.exists())
        directory.deleteRecursively()
    }
}

private class ThrowingRecorderBackend : VideoRecorderBackend {
    var stopCalls = 0

    override fun prepare(
        configuration: VideoRecorderConfiguration,
        file: java.io.File,
        onLimitReached: (VideoRecorderLimit) -> Unit,
    ): android.view.Surface = error("Not used by stop-focused tests")

    override fun start() = Unit
    override fun stop(): Nothing {
        stopCalls += 1
        throw RuntimeException("MediaRecorder already stopped at limit")
    }
    override fun reset() = Unit
    override fun release() = Unit
}
