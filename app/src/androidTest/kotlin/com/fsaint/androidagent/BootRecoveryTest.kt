package com.fsaint.androidagent

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull

@RunWith(AndroidJUnit4::class)
class BootRecoveryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun bootReceiverAndDeviceAdminAreExplicitlyRegistered() {
        val packageManager = context.packageManager
        val receiver = packageManager.getReceiverInfo(ComponentName(context, BootReceiver::class.java), 0)
        assertEquals(true, receiver.exported)
        assertNotNull(packageManager.getReceiverInfo(ComponentName(context, AgentDeviceAdminReceiver::class.java), 0))
    }

    @Test
    fun unrelatedBroadcastDoesNotCreateRestoreWork() {
        val previousScheduler = BootReceiver.scheduler
        val scheduler = RecordingRestoreScheduler()
        BootReceiver.scheduler = scheduler

        try {
            BootReceiver().onReceive(context, Intent(Intent.ACTION_TIME_CHANGED))

            assertEquals(0, scheduler.enqueueCalls)
        } finally {
            BootReceiver.scheduler = previousScheduler
        }
    }

    @Test
    fun lockedBootCompletedDefersRestoreWithoutTouchingWorkManager() {
        val previousScheduler = BootReceiver.scheduler
        val scheduler = RecordingRestoreScheduler()
        BootReceiver.scheduler = scheduler

        try {
            BootReceiver().onReceive(context, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))

            assertEquals(0, scheduler.enqueueCalls)
        } finally {
            BootReceiver.scheduler = previousScheduler
        }
    }

    @Test
    fun bootCompletedEnqueuesRestoreWork() {
        val previousScheduler = BootReceiver.scheduler
        val scheduler = RecordingRestoreScheduler()
        BootReceiver.scheduler = scheduler

        try {
            BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

            assertEquals(1, scheduler.enqueueCalls)
        } finally {
            BootReceiver.scheduler = previousScheduler
        }
    }

    @Test
    fun userUnlockedEnqueuesRestoreWork() {
        val previousScheduler = BootReceiver.scheduler
        val scheduler = RecordingRestoreScheduler()
        BootReceiver.scheduler = scheduler

        try {
            BootReceiver().onReceive(context, Intent(Intent.ACTION_USER_UNLOCKED))

            assertEquals(1, scheduler.enqueueCalls)
        } finally {
            BootReceiver.scheduler = previousScheduler
        }
    }

    @Test
    fun lockedBootRestoreRestoresDependenciesBeforeStartingForegroundService() = runBlocking {
        val previousCoordinator = BootRecoveryDependencies.coordinator
        val previousRestorer = BootRecoveryDependencies.restorer
        val previousStarter = BootRecoveryDependencies.foregroundStarter
        val coordinator = RecordingRecoveryCoordinator()
        val events = mutableListOf<String>()
        val starter = RecordingForegroundStarter()
        BootRecoveryDependencies.coordinator = coordinator
        BootRecoveryDependencies.restorer = BackgroundRuntimeRestorer { events += "restore" }
        BootRecoveryDependencies.foregroundStarter = AgentRuntimeForegroundStarter {
            starter.start(it)
            events += "start"
        }

        try {
            RuntimeRestoreWorker.restoreBackgroundRuntime(
                context,
                startBackgroundRuntime = {
                    starter.start(context)
                    events += "start"
                },
            )

            assertEquals(listOf("restore", "start"), events)
            assertEquals(1, starter.starts)
            assertEquals(0, coordinator.starts)
            assertEquals(0, coordinator.restores)
        } finally {
            BootRecoveryDependencies.coordinator = previousCoordinator
            BootRecoveryDependencies.restorer = previousRestorer
            BootRecoveryDependencies.foregroundStarter = previousStarter
        }
    }
}

private class RecordingRestoreScheduler : BackgroundRuntimeRestoreScheduler {
    var enqueueCalls = 0

    override fun enqueue(context: Context) {
        enqueueCalls += 1
    }
}

private class RecordingForegroundStarter : AgentRuntimeForegroundStarter {
    var starts = 0

    override fun start(context: Context) {
        starts += 1
    }
}

private class RecordingRecoveryCoordinator : AgentRuntimeRecovery {
    var starts = 0
    var stops = 0
    var restores = 0
    override val isRunning = false

    override fun start() {
        starts += 1
    }

    override suspend fun stop() {
        stops += 1
    }

    override suspend fun restore() {
        restores += 1
    }
}
