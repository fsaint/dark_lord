package com.fsaint.androidagent

import com.fsaint.androidagent.artifacts.ArtifactStore
import com.fsaint.androidagent.capabilities.audio.MicrophoneAdapter
import com.fsaint.androidagent.capabilities.audio.MicrophoneCapability
import com.fsaint.androidagent.capabilities.audio.MicrophoneLevelOutcome
import com.fsaint.androidagent.capabilities.audio.MicrophoneOperationOutcome
import com.fsaint.androidagent.capabilities.audio.MicrophonePermission
import com.fsaint.androidagent.capabilities.audio.MicrophoneStartRequest
import com.fsaint.androidagent.capabilities.audio.MicrophoneStopOutcome
import com.fsaint.androidagent.capabilities.camera.VideoClip
import com.fsaint.androidagent.model.ToolCall
import com.fsaint.androidagent.model.ToolError
import com.fsaint.androidagent.model.ToolResult
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class BackgroundJobManagerTest {
    @Test
    fun videoStartEntersRunningAndOwnsForegroundMediaService() = runTest {
        val foreground = FakeMediaForeground()
        val manager = manager(
            scope = backgroundScope,
            foreground = foreground,
            startVideo = { ToolResult(success = true, payload = Unit) },
        )

        val started = manager.start(ToolCall("jobs.start", mapOf("type" to "video", "durationMs" to "600000")))
        runCurrent()

        assertEquals(BackgroundJobStatus.RUNNING.name, startedStatus(manager, started))
        assertEquals(listOf(jobId(started)), foreground.started)
    }

    @Test
    fun stoppingVideoCompletesWithMp4Artifact() = runTest {
        val output = Files.createTempFile("video-job", ".mp4").toFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val foreground = FakeMediaForeground()
        val manager = manager(
            scope = backgroundScope,
            foreground = foreground,
            startVideo = { ToolResult(success = true, payload = Unit) },
            stopVideo = { ToolResult(success = true, payload = VideoClip(output, "video/mp4", 1280, 720, 15)) },
        )
        val started = manager.start(ToolCall("jobs.start", mapOf("type" to "video", "durationMs" to "600000")))
        runCurrent()

        val stopped = manager.stop(jobId(started), null)

        val payload = stopped.payload as Map<*, *>
        assertEquals(BackgroundJobStatus.COMPLETED.name, payload["status"])
        assertEquals("video/mp4", (payload["artifact"] as Map<*, *>)["mimeType"])
        assertEquals(listOf(jobId(started)), foreground.stopped)
    }

    @Test
    fun cancellingVideoKeepsTheFinalizedMp4Artifact() = runTest {
        val output = Files.createTempFile("video-cancel", ".mp4").toFile().apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val manager = manager(
            scope = backgroundScope,
            startVideo = { ToolResult(success = true, payload = Unit) },
            stopVideo = { ToolResult(success = true, payload = VideoClip(output, "video/mp4", 1280, 720, 15)) },
        )
        val started = manager.start(ToolCall("jobs.start", mapOf("type" to "video", "durationMs" to "600000")))
        runCurrent()

        val cancelled = manager.cancel(jobId(started))

        val payload = cancelled.payload as Map<*, *>
        assertEquals(BackgroundJobStatus.CANCELLED.name, payload["status"])
        assertEquals("video/mp4", (payload["artifact"] as Map<*, *>)["mimeType"])
    }

    @Test
    fun videoCameraStartWaitsForForegroundServiceAcknowledgement() = runTest {
        val acknowledgement = CompletableDeferred<Unit>()
        var startCalls = 0
        val manager = manager(
            scope = backgroundScope,
            foreground = object : MediaJobForeground {
                override suspend fun start(jobId: String) = acknowledgement.await()
                override fun stop(jobId: String) = Unit
            },
            startVideo = { startCalls += 1; ToolResult(success = true, payload = Unit) },
        )

        manager.start(ToolCall("jobs.start", mapOf("type" to "video", "durationMs" to "600000")))
        runCurrent()
        assertEquals(0, startCalls)

        acknowledgement.complete(Unit)
        runCurrent()
        assertEquals(1, startCalls)
    }

    @Test
    fun activeVideoPreventsMicrophoneJobFromStarting() = runTest {
        val manager = manager(
            scope = backgroundScope,
            startVideo = { ToolResult(success = true, payload = Unit) },
        )

        manager.start(ToolCall("jobs.start", mapOf("type" to "video", "durationMs" to "600000")))
        val microphone = manager.start(ToolCall("jobs.start", mapOf("type" to "audio")))

        assertEquals(ToolError.DEVICE_BUSY, microphone.error)
    }

    @Test
    fun mediaJobRejectsStartWhileDirectMediaHandlerOwnsSharedLease() = runTest {
        val lease = MediaResourceCoordinator()
        val controls = DirectMediaControlHandlers(
            lease = lease,
            onStartVideo = { ToolResult(success = true, payload = Unit) },
            onStopVideo = { ToolResult(false, error = ToolError.APP_NOT_RUNNING) },
            onStartMicrophone = { ToolResult(success = true, payload = Unit) },
            onStopMicrophone = { ToolResult<Any>(false, error = ToolError.APP_NOT_RUNNING) },
            onRecordMicrophone = { ToolResult(false, error = ToolError.APP_NOT_RUNNING) },
        )
        val manager = manager(scope = backgroundScope, lease = lease)

        val microphone = controls.handlers().getValue("microphone.start")(ToolCall("microphone.start"))
        val video = manager.start(ToolCall("jobs.start", mapOf("type" to "video")))

        assertEquals(true, microphone.success)
        assertEquals(ToolError.DEVICE_BUSY, video.error)
    }

    @Test
    fun idleDirectMediaStopDelegatesInsteadOfClaimingDeviceBusy() = runTest {
        var stopCalls = 0
        val controls = DirectMediaControlHandlers(
            lease = MediaResourceCoordinator(),
            onStartVideo = { ToolResult(success = true, payload = Unit) },
            onStopVideo = {
                stopCalls += 1
                ToolResult(false, error = ToolError.APP_NOT_RUNNING)
            },
            onStartMicrophone = { ToolResult(success = true, payload = Unit) },
            onStopMicrophone = { ToolResult<Any>(false, error = ToolError.APP_NOT_RUNNING) },
            onRecordMicrophone = { ToolResult(false, error = ToolError.APP_NOT_RUNNING) },
        )

        val stopped = controls.handlers().getValue("camera.stopVideo")(ToolCall("camera.stopVideo"))

        assertEquals(1, stopCalls)
        assertEquals(ToolError.APP_NOT_RUNNING, stopped.error)
    }

    @Test
    fun recreatingAnActiveVideoMarksItInterruptedWithoutRestartingIt() = runTest {
        val store = MemoryJobStore()
        val foreground = FakeMediaForeground()
        val original = manager(
            scope = backgroundScope,
            foreground = foreground,
            store = store,
            startVideo = { ToolResult(success = true, payload = Unit) },
        )
        val started = original.start(ToolCall("jobs.start", mapOf("type" to "video", "durationMs" to "600000")))

        val recreated = manager(scope = backgroundScope, foreground = foreground, store = store)

        val payload = recreated.status(jobId(started)).payload as Map<*, *>
        assertEquals(BackgroundJobStatus.INTERRUPTED.name, payload["status"])
        assertEquals(emptyList(), foreground.started)
    }

    private fun manager(
        scope: kotlinx.coroutines.CoroutineScope,
        foreground: MediaJobForeground = FakeMediaForeground(),
        store: MemoryJobStore = MemoryJobStore(),
        lease: MediaResourceLease = MediaResourceCoordinator(),
        startVideo: suspend (com.fsaint.androidagent.capabilities.camera.VideoStartRequest) -> ToolResult<Unit> = { ToolResult(false, error = ToolError.UNSUPPORTED) },
        stopVideo: suspend () -> ToolResult<VideoClip> = { ToolResult(false, error = ToolError.APP_NOT_RUNNING) },
    ) = BackgroundJobManager(
        artifacts = ArtifactStore(Files.createTempDirectory("background-jobs").toFile()),
        microphone = MicrophoneCapability(FakeMicrophoneAdapter),
        scope = scope,
        startVideo = startVideo,
        stopVideo = stopVideo,
        stateStore = store,
        mediaForeground = foreground,
        mediaLease = lease,
    )

    private fun jobId(result: ToolResult<Any>): String = (result.payload as Map<*, *>)["jobId"] as String

    private fun startedStatus(manager: BackgroundJobManager, result: ToolResult<Any>): String =
        (manager.status(jobId(result)).payload as Map<*, *>)["status"] as String

    private class FakeMediaForeground : MediaJobForeground {
        val started = mutableListOf<String>()
        val stopped = mutableListOf<String>()
        override suspend fun start(jobId: String) { started += jobId }
        override fun stop(jobId: String) { stopped += jobId }
    }

    private class MemoryJobStore : BackgroundJobStateStore {
        private val jobs = linkedMapOf<String, BackgroundJob>()
        override fun load(): List<BackgroundJob> = jobs.values.toList()
        override fun save(job: BackgroundJob) { jobs[job.id] = job }
    }

    private object FakeMicrophoneAdapter : MicrophoneAdapter {
        override fun permission() = MicrophonePermission.GRANTED
        override fun supported() = true
        override fun recording() = false
        override suspend fun start(request: MicrophoneStartRequest) = MicrophoneOperationOutcome.Success
        override suspend fun stop() = MicrophoneStopOutcome.NotRecording
        override fun level() = MicrophoneLevelOutcome.NotRecording
    }
}
