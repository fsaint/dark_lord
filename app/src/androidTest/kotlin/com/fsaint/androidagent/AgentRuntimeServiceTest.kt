package com.fsaint.androidagent

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import java.io.FileInputStream

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

        assertEquals(Service.START_NOT_STICKY, result)
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

    @Test
    fun notificationPermissionGateStartsImmediatelyBeforeAndroid13() {
        assertTrue(shouldStartBackgroundRuntimeWithNotificationPermission(Build.VERSION_CODES.S, permissionGranted = false))
    }

    @Test
    fun notificationPermissionGateRequiresGrantOnAndroid13AndNewer() {
        assertFalse(shouldStartBackgroundRuntimeWithNotificationPermission(Build.VERSION_CODES.TIRAMISU, permissionGranted = false))
        assertTrue(shouldStartBackgroundRuntimeWithNotificationPermission(Build.VERSION_CODES.TIRAMISU, permissionGranted = true))
    }

    @Test
    fun foregroundActivityLaunchStartsRealForegroundServiceWhenNotificationsAllowed() {
        grantPostNotificationsIfNeeded()
        val application = context.applicationContext as DarkLordApplication

        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                waitUntil { runtimeServiceDump().contains("isForeground=true") }
            }

            val dump = runtimeServiceDump()
            assertTrue(dump, dump.contains("isForeground=true"))
            assertTrue(dump, dump.contains("channel=agent_runtime"))
        } finally {
            application.stopBackgroundRuntime()
        }
    }

    private fun grantPostNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
            .close()
        waitUntil {
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun runtimeServiceDump(): String {
        val serviceName = "${context.packageName}/.AgentRuntimeService"
        return shell("dumpsys activity services $serviceName")
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
    }

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(50)
        }
        assertTrue("Condition was not met before timeout", predicate())
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
