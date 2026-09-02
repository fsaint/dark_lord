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

internal class VideoRecorderSession(
    context: Context,
    private val configuration: VideoRecorderConfiguration,
    private val handler: Handler,
    private val onLimitReached: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val started = CompletableDeferred<CameraOperationOutcome>()
    private val released = AtomicBoolean(false)
    private val stopLock = Any()
    private val finalizer = VideoRecordingFinalizer(configuration)
    private val limitReached = AtomicBoolean(false)
    private var mediaRecorder: MediaRecorder? = null
    private var captureSession: CameraCaptureSession? = null
    private var outputSurface: Surface? = null
    private var startedAtMs: Long? = null

    val file: File = File(appContext.filesDir, "camera-videos").also { directory ->
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Unable to create private video directory")
        }
    }.let { directory -> File.createTempFile("video-", ".mp4", directory) }

    fun outputSurface(): Surface {
        outputSurface?.let { return it }
        val recorder = MediaRecorder().apply {
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
                    limitReached.set(true)
                    onLimitReached()
                }
            }
            prepare()
        }
        mediaRecorder = recorder
        return recorder.surface.also { outputSurface = it }
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
                            mediaRecorder?.start() ?: error("Recorder was not prepared")
                            startedAtMs = SystemClock.elapsedRealtime()
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
            mediaRecorder?.stop() ?: throw IllegalStateException("Recorder was not prepared")
        } catch (error: RuntimeException) {
            if (limitReached.get()) {
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

    private fun recordedDurationMs(startedAt: Long): Long {
        val metadataDuration = runCatching {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            }
        }.getOrNull()
        return metadataDuration ?: (SystemClock.elapsedRealtime() - startedAt)
    }

    private fun releaseResources() {
        if (!released.compareAndSet(false, true)) return
        runCatching { captureSession?.close() }
        captureSession = null
        runCatching { outputSurface?.release() }
        outputSurface = null
        runCatching { mediaRecorder?.reset() }
        runCatching { mediaRecorder?.release() }
        mediaRecorder = null
    }
}
