package com.fsaint.androidagent.capabilities.screen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class ScreenCaptureForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Screen capture", NotificationManager.IMPORTANCE_LOW),
        )
        startForeground(
            NOTIFICATION_ID,
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("Dark Lord screen capture")
                .setContentText("A user-approved screen capture is active")
                .setOngoing(true)
                .build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PREPARE) {
            Handler(Looper.getMainLooper()).postDelayed(
                { stopSelfResult(startId) },
                PREPARED_SERVICE_TTL_MS,
            )
            return START_NOT_STICKY
        }
        val requestId = intent?.getStringExtra(EXTRA_REQUEST_ID)
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val resultData = intent?.intentExtra(EXTRA_RESULT_DATA)
        val request = intent?.captureRequest()
        if (requestId == null || resultCode == Int.MIN_VALUE || resultData == null || request == null) {
            requestId?.let { requests.remove(it)?.complete(ScreenCaptureOutcome.PermissionRequired) }
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        serviceScope.launch {
            val outcome = captureOnce(ProjectionGrant(resultCode, resultData), request)
            requests.remove(requestId)?.complete(outcome)
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        requests.values.forEach { it.complete(ScreenCaptureOutcome.Failed) }
        requests.clear()
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun captureOnce(
        grant: ProjectionGrant,
        request: ScreenCaptureRequest,
    ): ScreenCaptureOutcome {
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        val projection = try {
            projectionManager.getMediaProjection(grant.resultCode, grant.data)
        } catch (_: SecurityException) {
            return ScreenCaptureOutcome.PermissionRequired
        } ?: return ScreenCaptureOutcome.PermissionRequired

        val handlerThread = HandlerThread("dark-lord-screen-capture").apply { start() }
        val handler = Handler(handlerThread.looper)
        val result = CompletableDeferred<ScreenCaptureOutcome>()
        val dimensions = captureDimensions(request)
        val imageReader = ImageReader.newInstance(
            dimensions.width,
            dimensions.height,
            PixelFormat.RGBA_8888,
            2,
        )
        var display: VirtualDisplay? = null
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                result.complete(ScreenCaptureOutcome.PermissionRequired)
            }
        }

        return try {
            projection.registerCallback(callback, handler)
            imageReader.setOnImageAvailableListener({ reader ->
                if (result.isCompleted) return@setOnImageAvailableListener
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    result.complete(image.toCaptureOutcome(request))
                } finally {
                    image.close()
                }
            }, handler)
            display = projection.createVirtualDisplay(
                "DarkLordScreenCapture",
                dimensions.width,
                dimensions.height,
                dimensions.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                handler,
            )
            if (display == null) {
                ScreenCaptureOutcome.Unsupported
            } else {
                withTimeoutOrNull(CAPTURE_TIMEOUT_MS) { result.await() } ?: ScreenCaptureOutcome.TimedOut
            }
        } catch (_: SecurityException) {
            ScreenCaptureOutcome.PermissionRequired
        } catch (_: UnsupportedOperationException) {
            ScreenCaptureOutcome.Unsupported
        } catch (_: RuntimeException) {
            ScreenCaptureOutcome.Failed
        } finally {
            imageReader.setOnImageAvailableListener(null, null)
            display?.release()
            imageReader.close()
            runCatching { projection.stop() }
            runCatching { projection.unregisterCallback(callback) }
            handlerThread.quitSafely()
        }
    }

    private fun captureDimensions(request: ScreenCaptureRequest): CaptureDimensions {
        val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val displayWidth = max(1, bounds.width())
        val displayHeight = max(1, bounds.height())
        val scale = min(
            1f,
            min(request.maxWidth.toFloat() / displayWidth, request.maxHeight.toFloat() / displayHeight),
        )
        return CaptureDimensions(
            width = max(1, (displayWidth * scale).roundToInt()),
            height = max(1, (displayHeight * scale).roundToInt()),
            densityDpi = resources.displayMetrics.densityDpi,
        )
    }

    companion object {
        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 7007
        private const val ACTION_PREPARE = "com.fsaint.androidagent.screen.PREPARE"
        private const val EXTRA_REQUEST_ID = "request_id"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_MAX_WIDTH = "max_width"
        private const val EXTRA_MAX_HEIGHT = "max_height"
        private const val EXTRA_MAX_BYTES = "max_bytes"
        private const val CAPTURE_TIMEOUT_MS = 5_000L
        private const val PREPARED_SERVICE_TTL_MS = 60_000L
        private val requests = ConcurrentHashMap<String, CompletableDeferred<ScreenCaptureOutcome>>()

        internal fun intent(context: Context): Intent = Intent(context, ScreenCaptureForegroundService::class.java)

        internal fun prepare(context: Context) {
            context.startForegroundService(intent(context).setAction(ACTION_PREPARE))
        }

        internal suspend fun capture(
            context: Context,
            grant: ProjectionGrant,
            request: ScreenCaptureRequest,
        ): ScreenCaptureOutcome {
            val requestId = UUID.randomUUID().toString()
            val result = CompletableDeferred<ScreenCaptureOutcome>()
            requests[requestId] = result
            try {
                context.startService(
                    intent(context)
                        .putExtra(EXTRA_REQUEST_ID, requestId)
                        .putExtra(EXTRA_RESULT_CODE, grant.resultCode)
                        .putExtra(EXTRA_RESULT_DATA, grant.data)
                        .putExtra(EXTRA_MAX_WIDTH, request.maxWidth)
                        .putExtra(EXTRA_MAX_HEIGHT, request.maxHeight)
                        .putExtra(EXTRA_MAX_BYTES, request.maxBytes),
                )
            } catch (_: RuntimeException) {
                requests.remove(requestId)
                return ScreenCaptureOutcome.Failed
            }
            return withTimeoutOrNull(10_000) { result.await() }
                ?: ScreenCaptureOutcome.TimedOut.also { requests.remove(requestId) }
        }
    }
}

private data class CaptureDimensions(val width: Int, val height: Int, val densityDpi: Int)

private fun Intent.captureRequest(): ScreenCaptureRequest? {
    val width = getIntExtra("max_width", -1)
    val height = getIntExtra("max_height", -1)
    val bytes = getIntExtra("max_bytes", -1)
    return if (width > 0 && height > 0 && bytes > 0) ScreenCaptureRequest(width, height, bytes) else null
}

@Suppress("DEPRECATION")
private fun Intent.intentExtra(name: String): Intent? =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(name, Intent::class.java)
    } else {
        getParcelableExtra(name)
    }

private fun Image.toCaptureOutcome(request: ScreenCaptureRequest): ScreenCaptureOutcome {
    val plane = planes.firstOrNull() ?: return ScreenCaptureOutcome.Failed
    if (plane.pixelStride <= 0 || plane.rowStride < width * plane.pixelStride) {
        return ScreenCaptureOutcome.Failed
    }
    val paddedWidth = width + (plane.rowStride - width * plane.pixelStride) / plane.pixelStride
    val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
    return try {
        plane.buffer.rewind()
        padded.copyPixelsFromBuffer(plane.buffer)
        val cropped = if (paddedWidth == width) padded else Bitmap.createBitmap(padded, 0, 0, width, height)
        try {
            BoundedScreenImageEncoder.encode(cropped, request)
        } finally {
            if (cropped !== padded) cropped.recycle()
        }
    } catch (_: RuntimeException) {
        ScreenCaptureOutcome.Failed
    } finally {
        padded.recycle()
    }
}
