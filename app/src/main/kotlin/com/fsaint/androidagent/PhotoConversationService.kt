package com.fsaint.androidagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.fsaint.androidagent.voice.Speaker
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** One user-initiated photo turn; never automatically restarted or launched at boot. */
class PhotoConversationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var turn: Job? = null
    private var turnToken = -1L
    private val app get() = application as DarkLordApplication
    private val store get() = (application as DarkLordApplication).photoConversations

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val token = intent.getLongExtra("generation", -1)
            if (app.interactions.isCurrent(token)) app.beginLocalInteraction()
            return START_NOT_STICKY
        }
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        val token = intent.getLongExtra("generation", -1)
        if (!app.interactions.isCurrent(token)) { if (turn?.isActive != true) stopSelf(startId); return START_NOT_STICKY }
        turn?.cancel()
        turnToken = token
        val initial = PhotoConversationState(PhotoPhase.CAPTURING, "Taking a picture…")
        try {
            startForeground(NOTIFICATION_ID, notification(initial),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Foreground photo start rejected: ${error.javaClass.simpleName}")
            publish(PhotoConversationState(PhotoPhase.ERROR, "Android blocked photo capture. Open and unlock the phone, then try again."))
            stopSelf()
            return START_NOT_STICKY
        }
        val wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "$packageName:photo-conversation",
        ).apply { acquire(270_000L) }
        val speaker = app.interactions.speaker(token)
        turn = scope.launch(start = CoroutineStart.LAZY) {
            var chatId: String? = null
            val requestId = java.util.UUID.randomUUID().toString()
            fun publishTurn(state: PhotoConversationState) {
                if (app.interactions.isCurrent(token)) publish(state.copy(chatId = chatId, requestId = requestId))
            }
            try {
                chatId = app.outsideChat().id
                app.preparePhotoRequest(chatId, requestId, PHOTO_PROMPT, token)
                PhotoConversationRunner(
                    capture = { app.storeChatPhoto(app.capturePhotoForConversation()).also { app.attachPhotoRequest(requestId, it, token) } },
                    analyze = { artifactId -> app.runChatMessage(requireNotNull(chatId),
                        PHOTO_PROMPT, "CAPTURE", requestId, artifactId, expectedGeneration = token, preparedPhoto = true) },
                    publish = ::publishTurn,
                    speak = { answer ->
                        Log.i(TAG, "Requesting spoken photo answer")
                        withTimeoutOrNull(120_000L) {
                            suspendCancellableCoroutine<Unit> { continuation ->
                                speaker.speak(answer.take(3500)) {
                                    if (continuation.isActive) continuation.resume(Unit)
                                }
                                continuation.invokeOnCancellation { speaker.stop() }
                            }
                        }
                    },
                ).run()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                publishTurn(PhotoConversationState(PhotoPhase.ERROR, (error as? PhotoConversationFailure)?.message ?: "Could not start the photo conversation. Open Dark Lord to check setup."))
            } finally {
                runCatching { app.finishUnansweredPhoto(requestId, !app.interactions.isCurrent(token)) }
                    .onFailure { Log.w(TAG, "Could not persist photo termination: ${it.javaClass.simpleName}") }
                speaker.stop()
                if (wakeLock.isHeld) wakeLock.release()
                if (turnToken == token) {
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelf(startId)
                }
            }
        }
        app.interactions.attach(token, requireNotNull(turn))
        turn?.start()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        turn?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun publish(state: PhotoConversationState) {
        store.publish(state)
        Log.i(TAG, "Photo phase=${state.phase}")
        // A revoked notification permission must not prevent completion or speech.
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(state)) }
    }

    private fun notification(state: PhotoConversationState): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Photo conversations", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val title = when (state.phase) {
            PhotoPhase.CAPTURING -> "Dark Lord: taking a picture"
            PhotoPhase.PROCESSING -> "Dark Lord: analyzing picture"
            PhotoPhase.COMPLETE -> "Dark Lord: photo answer ready"
            PhotoPhase.ERROR -> "Dark Lord: photo needs attention"
        }
        val view = PendingIntent.getActivity(this, 52,
            Intent(this, MainActivity::class.java).putExtra("chat_id", state.chatId)
                .setData(android.net.Uri.parse("darklord://chat/${state.chatId}/${state.requestId}")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(title)
            .setContentText(state.text.take(400))
            .setStyle(Notification.BigTextStyle().bigText(state.text.take(16_000)))
            .setContentIntent(view)
            .setOnlyAlertOnce(true)
            .setOngoing(!state.terminal)
            .setAutoCancel(state.terminal)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle(title)
                .setContentText("Unlock to read the photo conversation.").build())
            .apply {
                if (!state.terminal) {
                    addAction(Notification.Action.Builder(null, "Cancel",
                        PendingIntent.getService(this@PhotoConversationService, 53,
                            Intent(this@PhotoConversationService, PhotoConversationService::class.java).setAction(ACTION_STOP)
                                .putExtra("generation", turnToken).setData(android.net.Uri.parse("darklord://stop-photo/$turnToken")),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build())
                }
            }.build()
    }

    companion object {
        private const val PHOTO_PROMPT = "Look at this picture, describe what you see, and suggest useful next actions."
        const val CHANNEL_ID = "photo_conversation"
        const val NOTIFICATION_ID = 52
        private const val ACTION_STOP = "com.fsaint.androidagent.photo.STOP"
        private const val TAG = "DarkLordPhoto"
    }
}
