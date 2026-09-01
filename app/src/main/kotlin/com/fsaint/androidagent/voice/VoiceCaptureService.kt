package com.fsaint.androidagent.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.fsaint.androidagent.DarkLordApplication
import com.fsaint.androidagent.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Microphone foreground service that keeps the process alive for one push-to-talk turn.
 *
 * The assistant session sends [ACTION_PRESS] when the side key is held and [ACTION_RELEASE] when the
 * session hides; neither stops the service. It stops itself once the turn returns to idle, after the
 * reply has been spoken, and mirrors the reply text into its notification so an answer is still visible
 * when the assistant surface has already gone.
 */
class VoiceCaptureService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var turnStarted = false
    private val controller: PushToTalkController
        get() = (application as DarkLordApplication).voiceTurn

    override fun onCreate() {
        super.onCreate()
        serviceScope.launch {
            controller.state.collect { state ->
                when (state) {
                    is VoiceTurnState.Responding -> notify(state.text)
                    is VoiceTurnState.Error -> notify(state.reason.spokenMessage)
                    VoiceTurnState.Idle -> if (turnStarted) {
                        Log.i(TAG, "turn complete; stopping")
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                    else -> Unit
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RELEASE -> {
                Log.i(TAG, "release")
                controller.released()
                if (!turnStarted) stopSelf(startId)
            }
            else -> press()
        }
        return START_NOT_STICKY
    }

    private fun press() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "press without microphone permission")
            controller.fail(VoiceTurnError.MICROPHONE_PERMISSION)
            stopSelf()
            return
        }
        Log.i(TAG, "press")
        startForeground(NOTIFICATION_ID, notification(getString(R.string.voice_capture_title)), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        turnStarted = true
        controller.pressed()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notify(text: String) {
        if (!turnStarted) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text.take(MAX_NOTIFICATION_CHARS)))
    }

    private fun notification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Voice capture", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.voice_capture_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .build()
    }

    companion object {
        const val ACTION_PRESS = "com.fsaint.androidagent.voice.PRESS"
        const val ACTION_RELEASE = "com.fsaint.androidagent.voice.RELEASE"

        fun pressIntent(context: Context): Intent = Intent(context, VoiceCaptureService::class.java).setAction(ACTION_PRESS)
        fun releaseIntent(context: Context): Intent = Intent(context, VoiceCaptureService::class.java).setAction(ACTION_RELEASE)

        private const val TAG = "DarkLordVoice"
        private const val CHANNEL_ID = "voice_capture"
        private const val NOTIFICATION_ID = 51
        private const val MAX_NOTIFICATION_CHARS = 400
    }
}
