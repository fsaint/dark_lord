package com.fsaint.androidagent.capabilities.notifications

import android.app.Notification
import android.service.notification.StatusBarNotification
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fsaint.androidagent.model.AgentEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class NotificationEventTest {
    @Test
    fun listenerPublishesOnlyAfterConnection() {
        val sink = RecordingNotificationEventSink()
        val service = AgentNotificationListenerService(sink)

        service.onNotificationPosted(notification("pkg", "title", "body"))
        service.onListenerConnected()
        service.onNotificationPosted(notification("pkg", "title", "body"))

        assertEquals(1, sink.events.size)
    }

    @Test
    fun listenerDoesNotPublishAfterDisconnection() {
        val sink = RecordingNotificationEventSink()
        val service = AgentNotificationListenerService(sink)

        service.onListenerConnected()
        service.onNotificationPosted(notification("pkg", "title", "body"))
        service.onListenerDisconnected()
        service.onNotificationPosted(notification("pkg", "title", "body"))

        assertEquals(1, sink.events.size)
    }

    @Test
    fun connectedListenerPublishesSanitizedNotificationFields() {
        val sink = RecordingNotificationEventSink()
        val service = AgentNotificationListenerService(sink)
        val posted = notification("pkg", "title", "body")

        service.onListenerConnected()
        service.onNotificationPosted(posted)

        val event = sink.events.single()
        assertEquals("notification.posted", event.type)
        assertEquals("pkg", event.source)
        assertEquals(1234L, event.occurredAtEpochMs)
        assertEquals(posted.key, event.payload["notificationKey"])
        assertEquals("pkg", event.payload["packageName"])
        assertEquals("", event.payload["category"])
        assertEquals("1234", event.payload["postTime"])
        assertEquals("title", event.payload["title"])
        assertEquals("body", event.payload["text"])
    }

    @Test
    fun connectedListenerUsesEmptyStringsForAbsentVisibleText() {
        val sink = RecordingNotificationEventSink()
        val service = AgentNotificationListenerService(sink)

        service.onListenerConnected()
        service.onNotificationPosted(notification("pkg", null, null))

        assertEquals("", sink.events.single().payload["title"])
        assertEquals("", sink.events.single().payload["text"])
    }

    @Suppress("DEPRECATION")
    private fun notification(packageName: String, title: String?, body: String?): StatusBarNotification =
        StatusBarNotification(
            packageName,
            packageName,
            7,
            "tag",
            1000,
            1000,
            0,
            Notification.Builder(androidx.test.core.app.ApplicationProvider.getApplicationContext())
                .setContentTitle(title)
                .setContentText(body)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build(),
            android.os.Process.myUserHandle(),
            1234L,
        )
}

private class RecordingNotificationEventSink : NotificationEventSink {
    val events = CopyOnWriteArrayList<AgentEvent>()

    override fun publish(event: AgentEvent) {
        events += event
    }
}
