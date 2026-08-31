package com.fsaint.androidagent

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentRuntimeServiceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun startCommandPromotesServiceBeforeStartingRuntimeOnce() {
        val events = mutableListOf<String>()
        val foreground = RecordingForegroundController(events)
        val coordinator = RecordingRuntimeCoordinator(events)
        val handler = AgentRuntimeServiceCommandHandler(
            coordinator = coordinator,
            foreground = foreground,
            scope = CoroutineScope(Dispatchers.Unconfined),
            stopSelfResult = { _: Int -> },
        )

        val result = handler.handle(Intent().setAction(AgentRuntimeService.ACTION_START), startId = 1)

        assertEquals(Service.START_STICKY, result)
        assertEquals(listOf("foreground.start", "runtime.start"), events)
        assertEquals(1, foreground.starts)
        assertEquals(1, coordinator.starts)
    }

    @Test
    fun restartCommandStopsRuntimeThenStartsItOnce() = runBlocking {
        val events = mutableListOf<String>()
        val foreground = RecordingForegroundController(events)
        val coordinator = RecordingRuntimeCoordinator(events)
        val handler = AgentRuntimeServiceCommandHandler(
            coordinator = coordinator,
            foreground = foreground,
            scope = CoroutineScope(Dispatchers.Unconfined),
            stopSelfResult = { _: Int -> },
        )

        val result = handler.handle(Intent().setAction(AgentRuntimeService.ACTION_RESTART), startId = 2)

        assertEquals(Service.START_STICKY, result)
        assertEquals(1, foreground.starts)
        assertEquals(1, coordinator.stops)
        assertEquals(1, coordinator.starts)
        assertTrue(events.indexOf("runtime.stop") < events.indexOf("runtime.start"))
        assertTrue(events.indexOf("foreground.start") < events.indexOf("runtime.start"))
    }

    @Test
    fun stopCommandStopsRuntimeAndService() = runBlocking {
        val events = mutableListOf<String>()
        val foreground = RecordingForegroundController(events)
        val coordinator = RecordingRuntimeCoordinator(events)
        val stoppedStartIds = mutableListOf<Int>()
        val handler = AgentRuntimeServiceCommandHandler(
            coordinator = coordinator,
            foreground = foreground,
            scope = CoroutineScope(Dispatchers.Unconfined),
            stopSelfResult = { stoppedStartIds += it },
        )

        val result = handler.handle(Intent().setAction(AgentRuntimeService.ACTION_STOP), startId = 3)

        assertEquals(Service.START_STICKY, result)
        assertEquals(1, coordinator.stops)
        assertEquals(1, foreground.stops)
        assertEquals(listOf(3), stoppedStartIds)
    }

    @Test
    fun notificationIsOngoingWithStopAction() {
        val factory = AgentRuntimeNotificationFactory(context)

        val notification = factory.build()

        assertTrue(notification.flags.and(Notification.FLAG_ONGOING_EVENT) != 0)
        assertTrue(notification.actions.any { it.title.toString() == "Stop" })
    }

    @Test
    fun notificationChannelUsesAgentRuntimeIdAndLowImportance() {
        val factory = AgentRuntimeNotificationFactory(context)

        factory.ensureChannel()

        val notifications = context.getSystemService(NotificationManager::class.java)
        val channel = notifications.getNotificationChannel(AgentRuntimeService.CHANNEL_ID)
        assertEquals(AgentRuntimeService.CHANNEL_ID, channel.id)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
    }

    @Test
    fun manifestRegistersNonExportedRemoteMessagingServiceWithoutSensorTypes() {
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, AgentRuntimeService::class.java),
            0,
        )

        assertFalse(info.exported)
        assertEquals(0, info.flags and ServiceInfo.FLAG_STOP_WITH_TASK)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
            info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
        )
        assertEquals(0, info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        assertEquals(0, info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }
}

private class RecordingForegroundController(
    private val events: MutableList<String> = mutableListOf(),
) : AgentRuntimeForegroundController {
    var starts = 0
    var stops = 0

    override fun ensureChannel() = Unit

    override fun start() {
        starts += 1
        events += "foreground.start"
    }

    override fun stop() {
        stops += 1
        events += "foreground.stop"
    }
}

private class RecordingRuntimeCoordinator(
    private val events: MutableList<String> = mutableListOf(),
) : AgentRuntimeRecovery {
    var starts = 0
    var stops = 0
    override var isRunning = false
        private set
    private var activeJob: CompletableJob? = null

    override fun start() {
        starts += 1
        isRunning = true
        activeJob = Job()
        events += "runtime.start"
    }

    override suspend fun stop() {
        stops += 1
        isRunning = false
        activeJob?.complete()
        events += "runtime.stop"
    }

    override suspend fun restore() = start()
}
