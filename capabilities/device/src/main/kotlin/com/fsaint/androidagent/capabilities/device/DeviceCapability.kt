package com.fsaint.androidagent.capabilities.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.ImageReader
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import com.fsaint.androidagent.model.AgentCapability
import com.fsaint.androidagent.model.AgentEvent
import com.fsaint.androidagent.model.AgentTool
import com.fsaint.androidagent.model.CapabilityStatus
import com.fsaint.androidagent.model.ToolError
import com.fsaint.androidagent.model.ToolCall
import com.fsaint.androidagent.model.ToolResult
import com.fsaint.androidagent.model.VerificationState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

data class DeviceTool(override val id: String) : AgentTool

class DeviceCapability(private val context: Context) : AgentCapability {
    override val id = "device.tools"
    override val version = "1.0"
    private var current = CapabilityStatus(available = true)

    override suspend fun initialize(): CapabilityStatus = current
    override fun tools(): List<AgentTool> = listOf(
        DeviceTool("device.battery"), DeviceTool("camera.capture"),
    )
    override fun events(): Flow<AgentEvent> = emptyFlow()
    override fun status(): CapabilityStatus = current

    fun battery(): ToolResult<Int> = ToolResult(
        success = true,
        payload = context.getSystemService(BatteryManager::class.java)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
        verification = VerificationState.VERIFIED,
    )

    @Suppress("MissingPermission") // Checked at method entry; Camera2 can still throw SecurityException.
    suspend fun captureCamera(): ToolResult<String> {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return ToolResult(false, error = ToolError.PERMISSION_REQUIRED, recoverable = true)
        }
        val manager = context.getSystemService(CameraManager::class.java)
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
                CameraMetadata.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull()
            ?: return ToolResult(false, error = ToolError.NOT_FOUND, recoverable = true)

        return suspendCancellableCoroutine { continuation ->
            val thread = HandlerThread("dark-lord-camera").apply { start() }
            val handler = Handler(thread.looper)
            val reader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 1)
            var device: CameraDevice? = null
            var session: CameraCaptureSession? = null
            var finished = false

            fun finish(result: ToolResult<String>) {
                if (finished) return
                finished = true
                session?.close()
                device?.close()
                reader.close()
                thread.quitSafely()
                if (continuation.isActive) continuation.resume(result)
            }

            reader.setOnImageAvailableListener({ imageReader ->
                val image = imageReader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val bytes = image.planes.first().buffer.let { buffer -> ByteArray(buffer.remaining()).also(buffer::get) }
                    val directory = File(context.cacheDir, "captures").apply { mkdirs() }
                    val file = File(directory, "capture-${System.currentTimeMillis()}.jpg")
                    file.outputStream().use { it.write(bytes) }
                    if (file.length() > 0L) {
                        finish(ToolResult(true, file.absolutePath, verification = VerificationState.VERIFIED))
                    } else {
                        finish(ToolResult(false, error = ToolError.DEVICE_BUSY, recoverable = true))
                    }
                } catch (_: Exception) {
                    finish(ToolResult(false, error = ToolError.DEVICE_BUSY, recoverable = true))
                } finally {
                    image.close()
                }
            }, handler)

            try {
                manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(opened: CameraDevice) {
                        device = opened
                        opened.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(configured: CameraCaptureSession) {
                                session = configured
                                try {
                                    configured.capture(
                                        opened.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                            addTarget(reader.surface)
                                        }.build(),
                                        object : CameraCaptureSession.CaptureCallback() {},
                                        handler,
                                    )
                                } catch (_: Exception) {
                                    finish(ToolResult(false, error = ToolError.DEVICE_BUSY, recoverable = true))
                                }
                            }

                            override fun onConfigureFailed(failed: CameraCaptureSession) {
                                finish(ToolResult(false, error = ToolError.DEVICE_BUSY, recoverable = true))
                            }
                        }, handler)
                    }

                    override fun onDisconnected(disconnected: CameraDevice) {
                        disconnected.close()
                        finish(ToolResult(false, error = ToolError.DEVICE_BUSY, recoverable = true))
                    }

                    override fun onError(failed: CameraDevice, error: Int) {
                        failed.close()
                        finish(ToolResult(false, error = ToolError.DEVICE_BUSY, recoverable = true))
                    }
                }, handler)
            } catch (_: SecurityException) {
                finish(ToolResult(false, error = ToolError.PERMISSION_REQUIRED, recoverable = true))
            } catch (_: Exception) {
                finish(ToolResult(false, error = ToolError.DEVICE_BUSY, recoverable = true))
            }
            continuation.invokeOnCancellation { finish(ToolResult(false, error = ToolError.TIMEOUT, recoverable = true)) }
        }
    }

    fun toolHandlers(): Map<String, suspend (ToolCall) -> ToolResult<Any>> = mapOf(
        "device.battery" to { _ ->
            battery().let { result ->
                ToolResult(result.success, result.payload as Any?, result.error, result.recoverable, result.verification)
            }
        },
        "camera.capture" to { _ ->
            captureCamera().let { result ->
                ToolResult(result.success, result.payload as Any?, result.error, result.recoverable, result.verification)
            }
        },
    )
}
