package com.fsaint.androidagent.capabilities.screen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

class AndroidScreenCaptureAdapter(context: Context) : ScreenCaptureAdapter {
    private val applicationContext = context.applicationContext
    private val projectionManager = applicationContext.getSystemService(MediaProjectionManager::class.java)
    private val grants = SingleUseGrantStore<ProjectionGrant>(GRANT_TTL_MS, SystemClock::elapsedRealtime)
    private val captureInProgress = AtomicBoolean(false)

    fun createConsentIntent(): Intent = projectionManager.createScreenCaptureIntent()

    fun acceptGrant(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            grants.clear()
            if (!captureInProgress.get()) applicationContext.stopService(ScreenCaptureForegroundService.intent(applicationContext))
            return
        }
        grants.put(ProjectionGrant(resultCode, Intent(data)))
        try {
            ScreenCaptureForegroundService.prepare(applicationContext)
        } catch (_: RuntimeException) {
            grants.clear()
        }
    }

    override fun grantState(): ScreenGrantState =
        if (grants.hasGrant()) ScreenGrantState.GRANTED else ScreenGrantState.NOT_GRANTED

    override suspend fun capture(request: ScreenCaptureRequest): ScreenCaptureOutcome {
        if (!captureInProgress.compareAndSet(false, true)) return ScreenCaptureOutcome.DeviceBusy
        return try {
            val approved = grants.take() ?: return ScreenCaptureOutcome.PermissionRequired
            ScreenCaptureForegroundService.capture(applicationContext, approved, request)
        } finally {
            captureInProgress.set(false)
        }
    }

    private companion object {
        const val GRANT_TTL_MS = 60_000L
    }
}

internal data class ProjectionGrant(val resultCode: Int, val data: Intent)
