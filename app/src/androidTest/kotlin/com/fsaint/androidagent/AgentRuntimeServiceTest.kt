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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AgentRuntimeServiceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun startCommandPromotesServiceBeforeStartingRuntimeOnce() = runTest {
        val events = mutableListOf<String>()
        val foreground = RecordingForegroundController(events)
        val coordinator = RecordingRuntimeCoordinator(events)
        val handler = AgentRuntimeServiceCommandHandler(
            coordinator = coordinator,
            foreground = foreground,
            scope = backgroundScope,
            stopSelfResult = { false },
        )

        val result = handler.handle(Intent().setAction(AgentRuntimeService.ACTION_START), startId = 1)
        runCurrent()

        assertEquals(Service.START_STICKY, result)
        assertEquals(listOf("foreground.start", "runtime.start"), events)
        assertEquals(1, foreground.starts)
        assertEquals(1, coordinator.starts)
    }

    @Test
    fun restartCommandStopsRuntimeThenStartsItOnce() = runTest {
        val events = mutableListOf<String>()
        val foreground = RecordingForegroundController(events)
        val coordinator = RecordingRuntimeCoordinator(events)
        val handler = AgentRuntimeServiceCommandHandler(
            coordinator = coordinator,
            foreground = foreground,
            scope = backgroundScope,
            stopSelfResult = { false },
        )

        val result = handler.handle(Intent().setAction(AgentRuntimeService.ACTION_RESTART), startId = 2)
        runCurrent()

        assertEquals(Service.START_STICKY, result)
        assertEquals(1, foreground.starts)
        assertEquals(1, coordinator.stops)
        assertEquals(1, coordinator.starts)
        assertTrue(events.indexOf("runtime.stop") < events.indexOf("runtime.start"))
        assertTrue(events.indexOf("foreground.start") < events.indexOf("runtime.start"))
    }

    @Test
    fun stopCommandStopsRuntimeAndService() = runTest {
        val events = mutableListOf<String>()
        val foreground = RecordingForegroundController(events)
        val coordinator = RecordingRuntimeCoordinator(events)
        val stoppedStartIds = mutableListOf<Int>()
        val handler = AgentRuntimeServiceCommandHandler(
            coordinator = coordinator,
            foreground = foreground,
            scope = backgroundScope,
            stopSelfResult = { stoppedStartIds += it; true },
        )

        val result = handler.handle(Intent().setAction(AgentRuntimeService.ACTION_STOP), startId = 3)
        runCurrent()

        assertEquals(Service.START_NOT_STICKY, result)
        assertEquals(1, coordinator.stops)
        assertEquals(0, foreground.stops)
        assertEquals(listOf(3), stoppedStartIds)
    }

    @Test
    fun stopThenOverlappingRestartIsSerializedAndRestartIsNotLost() = runTest {
        val events = mutableListOf<String>()
        val foreground = RecordingForegroundController(events)
        val coordinator = RecordingRuntimeCoordinator(events, blockFirstStop = true)
        val stoppedStartIds = mutableListOf<Int>()
        val handler = AgentRuntimeServiceCommandHandler(
            coordinator = coordinator,
            foreground = foreground,
            scope = backgroundScope,
            stopSelfResult = { stoppedStartIds += it; false },
        )

        handler.handle(Intent().setAction(AgentRuntimeService.ACTION_STOP), startId = 10)
        runCurrent()
        coordinator.stopEntered.await()
        handler.handle(Intent().setAction(AgentRuntimeService.ACTION_RESTART), startId = 11)
        coordinator.releaseStop.complete(Unit)
        runCurrent()

        assertEquals(2, coordinator.stops)
        assertEquals(1, coordinator.starts)
        assertEquals(listOf(10), stoppedStartIds)
        assertTrue(events.lastIndexOf("runtime.stop") < events.lastIndexOf("runtime.start"))
    }

    @Test
    fun shutdownStopsRuntimeBeforeRemovingForegroundNotification() = runTest {
        val events = mutableListOf<String>()
        val handler = AgentRuntimeServiceCommandHandler(
            coordinator = RecordingRuntimeCoordinator(events),
            foreground = RecordingForegroundController(events),
            scope = backgroundScope,
            stopSelfResult = { false },
        )

        handler.shutdown()

        assertEquals(listOf("runtime.stop", "foreground.stop"), events)
    }

    @Test
    fun stickyStartIsRejectedWhenRuntimeNotificationCannotBeShown() = runTest {
        val events = mutableListOf<String>()
        val stoppedStartIds = mutableListOf<Int>()
        val foreground = RecordingForegroundController(events)
        val coordinator = RecordingRuntimeCoordinator(events)
        val handler = AgentRuntimeServiceCommandHandler(
            coordinator = coordinator,
            foreground = foreground,
            scope = backgroundScope,
            stopSelfResult = { stoppedStartIds += it; true },
            notificationsAvailable = { false },
        )

        val result = handler.handle(intent = null, startId = 12)
        runCurrent()

        assertEquals(Service.START_NOT_STICKY, result)
        assertEquals(0, foreground.starts)
        assertEquals(0, coordinator.starts)
        assertEquals(1, coordinator.stops)
        assertEquals(listOf(12), stoppedStartIds)
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
    fun manifestRegistersNonExportedSpecialUseServiceWithoutSensorTypes() {
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, AgentRuntimeService::class.java),
            0,
        )

        assertFalse(info.exported)
        assertEquals(0, info.flags and ServiceInfo.FLAG_STOP_WITH_TASK)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        val subtype = context.packageManager.getProperty(
            "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE",
            ComponentName(context, AgentRuntimeService::class.java),
        )
        assertEquals(AgentRuntimeService.SPECIAL_USE_SUBTYPE, subtype.string)
        val permissions = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        ).requestedPermissions.orEmpty().toSet()
        assertTrue(Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE in permissions)
        assertFalse(Manifest.permission.FOREGROUND_SERVICE_REMOTE_MESSAGING in permissions)
        assertEquals(0, info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        assertEquals(0, info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    @Test
    fun notificationPermissionGateStartsImmediatelyBeforeAndroid13() {
        assertTrue(shouldRunBackgroundRuntime(Build.VERSION_CODES.S, permissionGranted = false, appNotificationsEnabled = true, channelImportance = NotificationManager.IMPORTANCE_LOW))
    }

    @Test
    fun notificationPermissionGateRequiresGrantOnAndroid13AndNewer() {
        assertFalse(shouldRunBackgroundRuntime(Build.VERSION_CODES.TIRAMISU, permissionGranted = false, appNotificationsEnabled = true, channelImportance = NotificationManager.IMPORTANCE_LOW))
        assertTrue(shouldRunBackgroundRuntime(Build.VERSION_CODES.TIRAMISU, permissionGranted = true, appNotificationsEnabled = true, channelImportance = NotificationManager.IMPORTANCE_LOW))
    }

    @Test
    fun notificationGateRejectsDisabledAppAndRuntimeChannel() {
        assertFalse(shouldRunBackgroundRuntime(Build.VERSION_CODES.VANILLA_ICE_CREAM, permissionGranted = true, appNotificationsEnabled = false, channelImportance = NotificationManager.IMPORTANCE_LOW))
        assertFalse(shouldRunBackgroundRuntime(Build.VERSION_CODES.VANILLA_ICE_CREAM, permissionGranted = true, appNotificationsEnabled = true, channelImportance = NotificationManager.IMPORTANCE_NONE))
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
    private val blockFirstStop: Boolean = false,
) : AgentRuntimeRecovery {
    var starts = 0
    var stops = 0
    override var isRunning = false
        private set
    private var activeJob: CompletableJob? = null
    val stopEntered = CompletableDeferred<Unit>()
    val releaseStop = CompletableDeferred<Unit>()

    override fun start() {
        starts += 1
        isRunning = true
        activeJob = Job()
        events += "runtime.start"
    }

    override suspend fun stop() {
        stops += 1
        if (blockFirstStop && stops == 1) {
            stopEntered.complete(Unit)
            releaseStop.await()
        }
        isRunning = false
        activeJob?.complete()
        events += "runtime.stop"
    }

    override suspend fun restore() = start()
}
