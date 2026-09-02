package com.fsaint.androidagent.capabilities.camera

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Handler
import android.os.SystemClock
import android.view.Surface
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred

internal data class VideoRecorderConfiguration private constructor(
    val width: Int,
    val height: Int,
    val maxDurationMs: Int,
    val maxBytes: Long,
    val outputFormat: String,
    val videoEncoder: String,
    val audioEncoder: String,
) {
    companion object {
        const val MP4 = "mp4"
        const val H264 = "h264"
        const val AAC = "aac"
        private const val MAX_DURATION_MS = 600_000L

        fun create(width: Int, height: Int, maxDurationMs: Long, maxBytes: Int): VideoRecorderConfiguration =
            VideoRecorderConfiguration(
                width = width,
                height = height,
                maxDurationMs = maxDurationMs.coerceIn(1L, MAX_DURATION_MS).toInt(),
                maxBytes = maxBytes.toLong(),
                outputFormat = MP4,
                videoEncoder = H264,
                audioEncoder = AAC,
            )
    }
}

internal class VideoRecordingFinalizer(
    private val configuration: VideoRecorderConfiguration,
) {
    private var completedClip: VideoClip? = null

    @Synchronized
    fun completed(): VideoClip? = completedClip

    @Synchronized
    fun finalize(file: File, durationMs: Long): VideoClip? {
        completedClip?.let { return it }
        if (file.length() !in 1..configuration.maxBytes || durationMs !in 1..configuration.maxDurationMs) {
            file.delete()
            return null
        }
        return VideoClip(
            file = file,
            mimeType = "video/mp4",
            width = configuration.width,
            height = configuration.height,
            durationMs = durationMs,
        ).also { completedClip = it }
    }

    @Synchronized
    fun abort(file: File) {
        if (completedClip == null) file.delete()
    }
}

internal interface VideoRecorderBackend {
    fun prepare(
        configuration: VideoRecorderConfiguration,
        file: File,
        onLimitReached: (VideoRecorderLimit) -> Unit,
    ): Surface

    fun start()
    fun stop()
    fun reset()
    fun release()
}

internal enum class VideoRecorderLimit {
    DURATION,
    FILE_SIZE,
}

private class AndroidVideoRecorderBackend : VideoRecorderBackend {
    private val recorder = MediaRecorder()

    override fun prepare(
        configuration: VideoRecorderConfiguration,
        file: File,
        onLimitReached: (VideoRecorderLimit) -> Unit,
    ): Surface = recorder.apply {
        setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
        setVideoSource(MediaRecorder.VideoSource.SURFACE)
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        setVideoSize(configuration.width, configuration.height)
        setMaxDuration(configuration.maxDurationMs)
        setMaxFileSize(configuration.maxBytes)
        setOutputFile(file.absolutePath)
        setOnInfoListener { _, what, _ ->
            if (
                what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED
            ) {
                onLimitReached(
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        VideoRecorderLimit.DURATION
                    } else {
                        VideoRecorderLimit.FILE_SIZE
                    },
                )
            }
        }
        prepare()
    }.surface

    override fun start() = recorder.start()
    override fun stop() = recorder.stop()
    override fun reset() = recorder.reset()
    override fun release() = recorder.release()
}

internal class VideoRecorderSession private constructor(
    private val configuration: VideoRecorderConfiguration,
    private val handler: Handler?,
    private val onLimitReached: () -> Unit,
    val file: File,
    private val recorder: VideoRecorderBackend,
    private val clockMs: () -> Long,
    private val metadataDurationMs: (File) -> Long?,
) {
    private val started = CompletableDeferred<CameraOperationOutcome>()
    private val released = AtomicBoolean(false)
    private val stopLock = Any()
    private val finalizer = VideoRecordingFinalizer(configuration)
    private val limitSignaled = AtomicBoolean(false)
    private var captureSession: CameraCaptureSession? = null
    private var outputSurface: Surface? = null
    private var startedAtMs: Long? = null

    constructor(
        context: Context,
        configuration: VideoRecorderConfiguration,
        handler: Handler,
        onLimitReached: () -> Unit,
    ) : this(
        configuration = configuration,
        handler = handler,
        onLimitReached = onLimitReached,
        file = createOutputFile(context),
        recorder = AndroidVideoRecorderBackend(),
        clockMs = SystemClock::elapsedRealtime,
        metadataDurationMs = ::readMetadataDurationMs,
    )

    fun outputSurface(): Surface {
        outputSurface?.let { return it }
        return recorder.prepare(configuration, file, ::limitReached).also { outputSurface = it }
    }

    fun start(camera: CameraDevice, surface: Surface) {
        try {
            camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(surface)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            }.build()
                            session.setRepeatingRequest(request, null, handler)
                            recorder.start()
                            recordingStarted()
                            started.complete(CameraOperationOutcome.Success)
                        } catch (_: SecurityException) {
                            started.complete(CameraOperationOutcome.PermissionRequired)
                        } catch (error: CameraAccessException) {
                            started.complete(error.toOperationOutcome())
                        } catch (_: RuntimeException) {
                            started.complete(CameraOperationOutcome.Failed)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        started.complete(CameraOperationOutcome.DeviceBusy)
                    }
                },
                            handler,
            )
        } catch (_: SecurityException) {
            started.complete(CameraOperationOutcome.PermissionRequired)
        } catch (error: CameraAccessException) {
            started.complete(error.toOperationOutcome())
        } catch (_: RuntimeException) {
            started.complete(CameraOperationOutcome.Failed)
        }
    }

    suspend fun awaitStarted(): CameraOperationOutcome = started.await()

    fun stop(): VideoClip = synchronized(stopLock) {
        finalizer.completed()?.let { return@synchronized it }
        val startedAt = startedAtMs ?: throw IllegalStateException("Video recording did not start")
        try {
            recorder.stop()
        } catch (error: RuntimeException) {
            if (limitSignaled.get()) {
                finalizer.finalize(file, recordedDurationMs(startedAt))?.let { return@synchronized it }
            }
            finalizer.abort(file)
            throw error
        } finally {
            releaseResources()
        }
        finalizer.finalize(file, recordedDurationMs(startedAt))
            ?: throw IllegalStateException("Recorder output exceeded its bounds or was empty")
    }

    fun abort() = synchronized(stopLock) {
        releaseResources()
        finalizer.abort(file)
    }

    internal fun recordingStarted() {
        startedAtMs = clockMs()
    }

    internal fun limitReached(limit: VideoRecorderLimit) {
        limitSignaled.set(true)
        onLimitReached()
    }

    private fun recordedDurationMs(startedAt: Long): Long {
        return metadataDurationMs(file) ?: (clockMs() - startedAt)
    }

    private fun releaseResources() {
        if (!released.compareAndSet(false, true)) return
        runCatching { captureSession?.close() }
        captureSession = null
        runCatching { outputSurface?.release() }
        outputSurface = null
        runCatching { recorder.reset() }
        runCatching { recorder.release() }
    }

    companion object {
        internal fun forRecordingTest(
            configuration: VideoRecorderConfiguration,
            file: File,
            recorder: VideoRecorderBackend,
            clockMs: () -> Long,
        ): VideoRecorderSession = VideoRecorderSession(
            configuration = configuration,
            handler = null,
            onLimitReached = {},
            file = file,
            recorder = recorder,
            clockMs = clockMs,
            metadataDurationMs = { null },
        )

        private fun createOutputFile(context: Context): File =
            File(context.applicationContext.filesDir, "camera-videos").also { directory ->
                if (!directory.exists() && !directory.mkdirs()) {
                    throw IllegalStateException("Unable to create private video directory")
                }
            }.let { directory -> File.createTempFile("video-", ".mp4", directory) }

        private fun readMetadataDurationMs(file: File): Long? = runCatching {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            }
        }.getOrNull()
    }
}
