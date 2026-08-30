package com.fsaint.androidagent.capabilities.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.fsaint.androidagent.model.AgentEvent

fun interface NotificationEventSink {
    fun publish(event: AgentEvent)
}

/** Process-wide application wiring used when Android creates the manifest service. */
object AgentNotificationListenerServiceDependencies {
    @Volatile
    private var eventSink: NotificationEventSink = NoOpNotificationEventSink

    fun configure(eventSink: NotificationEventSink) {
        this.eventSink = eventSink
    }

    internal fun sink(): NotificationEventSink = eventSink
}

class AgentNotificationListenerService(
    private val sink: NotificationEventSink = AgentNotificationListenerServiceDependencies.sink(),
) : NotificationListenerService() {
    private var connected = false

    override fun onListenerConnected() {
        connected = true
    }

    override fun onListenerDisconnected() {
        connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!connected) return

        val notification = sbn.notification
        sink.publish(
            AgentEvent(
                id = "notification:${sbn.key}:${sbn.postTime}",
                type = "notification.posted",
                source = sbn.packageName,
                occurredAtEpochMs = sbn.postTime,
                payload = mapOf(
                    "notificationKey" to sbn.key,
                    "packageName" to sbn.packageName,
                    "category" to notification.category.orEmpty(),
                    "postTime" to sbn.postTime.toString(),
                    "title" to notification.extras.getCharSequence(Notification.EXTRA_TITLE).orEmpty(),
                    "text" to notification.extras.getCharSequence(Notification.EXTRA_TEXT).orEmpty(),
                ),
            ),
        )
    }
}

private fun CharSequence?.orEmpty(): String = if (this == null) "" else toString()

private object NoOpNotificationEventSink : NotificationEventSink {
    override fun publish(event: AgentEvent) = Unit
}
