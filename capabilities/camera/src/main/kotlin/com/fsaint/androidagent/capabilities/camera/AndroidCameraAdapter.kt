package com.fsaint.androidagent.capabilities.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

class AndroidCameraAdapter(context: Context) : CameraAdapter {
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(CameraManager::class.java)
    private val videoLock = Any()
    private var activeVideo: ActiveVideo? = null
    private var completedVideo: VideoClip? = null
    private var videoStarting = false

    override fun permission(): CameraPermission = if (
        appContext.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    ) {
        CameraPermission.GRANTED
    } else {
        CameraPermission.DENIED
    }

    override fun supported(): Boolean =
        appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    override suspend fun list(): CameraListOutcome {
        if (!supported()) return CameraListOutcome.Unsupported
        if (permission() != CameraPermission.GRANTED) return CameraListOutcome.PermissionRequired
        return try {
            CameraListOutcome.Success(cameraManager.cameraIdList.map(::describe))
        } catch (_: SecurityException) {
            CameraListOutcome.PermissionRequired
        } catch (_: CameraAccessException) {
            CameraListOutcome.Unsupported
        }
    }

    override suspend fun capture(request: CameraCaptureRequest): CameraCaptureOutcome {
        if (!supported()) return CameraCaptureOutcome.Unsupported
        if (permission() != CameraPermission.GRANTED) return CameraCaptureOutcome.PermissionRequired

        val cameraId = try {
            request.cameraId ?: preferredCameraId() ?: return CameraCaptureOutcome.NotFound
        } catch (_: CameraAccessException) {
            return CameraCaptureOutcome.Unsupported
        }
        val size = try {
            chooseJpegSize(cameraId, request) ?: return CameraCaptureOutcome.Unsupported
        } catch (_: IllegalArgumentException) {
            return CameraCaptureOutcome.NotFound
        } catch (_: CameraAccessException) {
            return CameraCaptureOutcome.Unsupported
        }

        return capture(cameraId, size, request)
    }

    override suspend fun setTorch(cameraId: String, enabled: Boolean): CameraOperationOutcome {
        if (!supported()) return CameraOperationOutcome.Unsupported
        if (permission() != CameraPermission.GRANTED) return CameraOperationOutcome.PermissionRequired
        return try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            if (characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) != true) {
                CameraOperationOutcome.Unsupported
            } else {
                cameraManager.setTorchMode(cameraId, enabled)
                CameraOperationOutcome.Success
            }
        } catch (_: IllegalArgumentException) {
            CameraOperationOutcome.NotFound
        } catch (_: SecurityException) {
            CameraOperationOutcome.PermissionRequired
        } catch (error: CameraAccessException) {
            error.toOperationOutcome()
        }
    }

    override suspend fun startVideo(request: VideoStartRequest): CameraOperationOutcome {
        if (!supported()) return CameraOperationOutcome.Unsupported
        if (!hasVideoPermissions()) return CameraOperationOutcome.PermissionRequired
        synchronized(videoLock) {
            if (videoStarting || activeVideo != null) return CameraOperationOutcome.DeviceBusy
            videoStarting = true
            completedVideo = null
        }

        return try {
            startVideoRecording(request)
        } finally {
            synchronized(videoLock) { videoStarting = false }
        }
    }

    override suspend fun stopVideo(): CameraVideoStopOutcome {
        val existing = synchronized(videoLock) {
            completedVideo?.also { completedVideo = null }
        }
        if (existing != null) return CameraVideoStopOutcome.Success(existing)

        val video = synchronized(videoLock) {
            activeVideo?.also { activeVideo = null }
        } ?: return CameraVideoStopOutcome.NotFound
        return finishVideo(video, retainClip = false)
    }

    override fun recordingVideo(): Boolean = synchronized(videoLock) { activeVideo != null }

    private fun describe(cameraId: String): CameraDescription {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        return CameraDescription(
            id = cameraId,
            lensFacing = when (characteristics.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_FRONT -> CameraLensFacing.FRONT
                CameraCharacteristics.LENS_FACING_BACK -> CameraLensFacing.BACK
                CameraCharacteristics.LENS_FACING_EXTERNAL -> CameraLensFacing.EXTERNAL
                else -> CameraLensFacing.UNKNOWN
            },
            flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
        )
    }

    private fun preferredCameraId(): String? {
        val ids = cameraManager.cameraIdList
        return ids.firstOrNull {
            cameraManager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: ids.firstOrNull()
    }

    private fun chooseJpegSize(cameraId: String, request: CameraCaptureRequest): Size? = cameraManager
        .getCameraCharacteristics(cameraId)
        .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        ?.getOutputSizes(ImageFormat.JPEG)
        ?.filter { it.width <= request.maxWidth && it.height <= request.maxHeight }
        ?.maxByOrNull { it.width.toLong() * it.height }

    private fun hasVideoPermissions(): Boolean =
        permission() == CameraPermission.GRANTED &&
            appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private suspend fun startVideoRecording(request: VideoStartRequest): CameraOperationOutcome {
        val cameraId = try {
            request.cameraId ?: preferredCameraId() ?: return CameraOperationOutcome.NotFound
        } catch (_: CameraAccessException) {
            return CameraOperationOutcome.Unsupported
        }
        val size = try {
            chooseVideoSize(cameraId, request) ?: return CameraOperationOutcome.Unsupported
        } catch (_: IllegalArgumentException) {
            return CameraOperationOutcome.NotFound
        } catch (_: CameraAccessException) {
            return CameraOperationOutcome.Unsupported
        }

        val thread = HandlerThread("dark-lord-video-camera").apply { start() }
        val handler = Handler(thread.looper)
        var camera: CameraDevice? = null
        var recorder: VideoRecorderSession? = null
        var timeout: Runnable? = null
        var recordingStarted = false
        try {
            val configuration = VideoRecorderConfiguration.create(
                width = size.width,
                height = size.height,
                maxDurationMs = request.maxDurationMs,
                maxBytes = request.maxBytes,
            )
            val recorderSession = VideoRecorderSession(appContext, configuration, handler) {
                handler.post { stopVideoAtLimit() }
            }
            recorder = recorderSession
            val openedCamera = when (val opened = openVideoCamera(cameraId, handler)) {
                is VideoCameraOpen.Success -> opened.camera
                is VideoCameraOpen.Failure -> return opened.outcome
            }
            camera = openedCamera
            val surface = recorderSession.outputSurface()
            recorderSession.start(openedCamera, surface)
            val started = withTimeoutOrNull(VIDEO_START_TIMEOUT_MS) { recorderSession.awaitStarted() }
                ?: CameraOperationOutcome.Failed
            if (started != CameraOperationOutcome.Success) return started

            val active = ActiveVideo(openedCamera, recorderSession, thread, handler)
            timeout = Runnable { stopVideoAtLimit() }
            synchronized(videoLock) {
                activeVideo = active
            }
            handler.postDelayed(timeout, configuration.maxDurationMs.toLong())
            recordingStarted = true
            camera = null
            recorder = null
            return CameraOperationOutcome.Success
        } catch (_: SecurityException) {
            return CameraOperationOutcome.PermissionRequired
        } catch (error: CameraAccessException) {
            return error.toOperationOutcome()
        } catch (_: IllegalArgumentException) {
            return CameraOperationOutcome.NotFound
        } catch (_: RuntimeException) {
            return CameraOperationOutcome.Failed
        } finally {
            if (!recordingStarted) timeout?.let(handler::removeCallbacks)
            recorder?.abort()
            camera?.close()
            if (!recordingStarted) thread.quitSafely()
        }
    }

    private fun chooseVideoSize(cameraId: String, request: VideoStartRequest): Size? {
        val targetWidth = minOf(SAFE_VIDEO_WIDTH, request.maxWidth)
        val targetHeight = minOf(SAFE_VIDEO_HEIGHT, request.maxHeight)
        return cameraManager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(MediaRecorder::class.java)
            ?.filter { it.width <= targetWidth && it.height <= targetHeight }
            ?.maxByOrNull { it.width.toLong() * it.height }
    }

    @SuppressLint("MissingPermission")
    private suspend fun openVideoCamera(cameraId: String, handler: Handler): VideoCameraOpen {
        val opened = CompletableDeferred<VideoCameraOpen>()
        cameraManager.openCamera(
            cameraId,
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (!opened.complete(VideoCameraOpen.Success(camera))) camera.close()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    opened.complete(VideoCameraOpen.Failure(CameraOperationOutcome.DeviceBusy))
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    opened.complete(VideoCameraOpen.Failure(error.toOperationOutcome()))
                }
            },
            handler,
        )
        return withTimeoutOrNull(VIDEO_START_TIMEOUT_MS) { opened.await() }
            ?: VideoCameraOpen.Failure(CameraOperationOutcome.Failed)
    }

    private fun stopVideoAtLimit() {
        val video = synchronized(videoLock) {
            activeVideo?.also { activeVideo = null }
        } ?: return
        finishVideo(video, retainClip = true)
    }

    private fun finishVideo(video: ActiveVideo, retainClip: Boolean): CameraVideoStopOutcome = try {
        val clip = video.recorder.stop()
        video.camera.close()
        video.thread.quitSafely()
        if (retainClip) synchronized(videoLock) { completedVideo = clip }
        CameraVideoStopOutcome.Success(clip)
    } catch (_: SecurityException) {
        video.recorder.abort()
        video.camera.close()
        video.thread.quitSafely()
        CameraVideoStopOutcome.PermissionRequired
    } catch (_: RuntimeException) {
        video.recorder.abort()
        video.camera.close()
        video.thread.quitSafely()
        CameraVideoStopOutcome.Failed
    }

    private data class ActiveVideo(
        val camera: CameraDevice,
        val recorder: VideoRecorderSession,
        val thread: HandlerThread,
        val handler: Handler,
    )

    private sealed interface VideoCameraOpen {
        data class Success(val camera: CameraDevice) : VideoCameraOpen
        data class Failure(val outcome: CameraOperationOutcome) : VideoCameraOpen
    }

    @SuppressLint("MissingPermission")
    private suspend fun capture(
        cameraId: String,
        size: Size,
        request: CameraCaptureRequest,
    ): CameraCaptureOutcome {
        val thread = HandlerThread("dark-lord-camera").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
        val outcome = CompletableDeferred<CameraCaptureOutcome>()
        val completed = AtomicBoolean(false)
        var camera: CameraDevice? = null
        var session: CameraCaptureSession? = null

        fun complete(value: CameraCaptureOutcome) {
            if (completed.compareAndSet(false, true)) outcome.complete(value)
        }

        reader.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            image.use {
                val buffer = it.planes.first().buffer
                if (buffer.remaining() > request.maxBytes) {
                    complete(CameraCaptureOutcome.OsRestricted)
                } else {
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    val rotation = try {
                        cameraManager.getCameraCharacteristics(cameraId)
                            .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                    } catch (_: CameraAccessException) {
                        0
                    }
                    complete(
                        CameraCaptureOutcome.Success(
                            CameraImage(bytes, size.width, size.height, "image/jpeg", rotation.normalizedRotation()),
                        ),
                    )
                }
            }
        }, handler)

        try {
            cameraManager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        camera = device
                        configureCapture(device, reader, handler, { complete(it) }, { session = it })
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        device.close()
                        complete(CameraCaptureOutcome.DeviceBusy)
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        device.close()
                        complete(error.toCaptureOutcome())
                    }
                },
                handler,
            )
            return withTimeoutOrNull(10_000) { outcome.await() } ?: CameraCaptureOutcome.TimedOut
        } catch (_: SecurityException) {
            return CameraCaptureOutcome.PermissionRequired
        } catch (error: CameraAccessException) {
            return error.toCaptureOutcome()
        } catch (_: IllegalArgumentException) {
            return CameraCaptureOutcome.NotFound
        } finally {
            session?.close()
            camera?.close()
            reader.close()
            thread.quitSafely()
        }
    }

    private fun configureCapture(
        camera: CameraDevice,
        reader: ImageReader,
        handler: Handler,
        complete: (CameraCaptureOutcome) -> Unit,
        retainSession: (CameraCaptureSession) -> Unit,
    ) {
        camera.createCaptureSession(
            listOf(reader.surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    retainSession(session)
                    try {
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(reader.surface)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        }.build()
                        session.capture(
                            request,
                            object : CameraCaptureSession.CaptureCallback() {
                                override fun onCaptureFailed(
                                    session: CameraCaptureSession,
                                    request: CaptureRequest,
                                    failure: CaptureFailure,
                                ) {
                                    complete(CameraCaptureOutcome.Failed)
                                }

                                override fun onCaptureCompleted(
                                    session: CameraCaptureSession,
                                    request: CaptureRequest,
                                    result: TotalCaptureResult,
                                ) = Unit
                            },
                            handler,
                        )
                    } catch (error: CameraAccessException) {
                        complete(error.toCaptureOutcome())
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    complete(CameraCaptureOutcome.DeviceBusy)
                }
            },
            handler,
        )
    }
}

private const val SAFE_VIDEO_WIDTH = 1280
private const val SAFE_VIDEO_HEIGHT = 720
private const val VIDEO_START_TIMEOUT_MS = 10_000L

private fun Int.normalizedRotation(): Int = when (this) {
    90, 180, 270 -> this
    else -> 0
}

private fun Int.toCaptureOutcome(): CameraCaptureOutcome = when (this) {
    CameraDevice.StateCallback.ERROR_CAMERA_IN_USE,
    CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE,
    CameraDevice.StateCallback.ERROR_CAMERA_DEVICE,
    -> CameraCaptureOutcome.DeviceBusy
    CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> CameraCaptureOutcome.OsRestricted
    else -> CameraCaptureOutcome.Failed
}

private fun Int.toOperationOutcome(): CameraOperationOutcome = when (this) {
    CameraDevice.StateCallback.ERROR_CAMERA_IN_USE,
    CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE,
    CameraDevice.StateCallback.ERROR_CAMERA_DEVICE,
    -> CameraOperationOutcome.DeviceBusy
    CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> CameraOperationOutcome.OsRestricted
    else -> CameraOperationOutcome.Failed
}

private fun CameraAccessException.toCaptureOutcome(): CameraCaptureOutcome = when (reason) {
    CameraAccessException.CAMERA_IN_USE,
    CameraAccessException.MAX_CAMERAS_IN_USE,
    CameraAccessException.CAMERA_DISCONNECTED,
    -> CameraCaptureOutcome.DeviceBusy
    CameraAccessException.CAMERA_DISABLED -> CameraCaptureOutcome.OsRestricted
    else -> CameraCaptureOutcome.Failed
}

internal fun CameraAccessException.toOperationOutcome(): CameraOperationOutcome = when (reason) {
    CameraAccessException.CAMERA_IN_USE,
    CameraAccessException.MAX_CAMERAS_IN_USE,
    CameraAccessException.CAMERA_DISCONNECTED,
    -> CameraOperationOutcome.DeviceBusy
    CameraAccessException.CAMERA_DISABLED -> CameraOperationOutcome.OsRestricted
    else -> CameraOperationOutcome.Failed
}
