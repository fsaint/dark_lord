package com.fsaint.androidagent

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.fsaint.androidagent.ui.OpenAssistantScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackgroundRuntimeRecoveryTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun lockedBootCompletedDoesNotEnqueueRestoreRequest() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .build(),
        )
        val workManager = WorkManager.getInstance(context)
        workManager.cancelAllWork()
        waitForWorkIdle(workManager)
        workManager.pruneWork()

        val receiver = BootReceiver()
        receiver.onReceive(context, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        receiver.onReceive(context, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))

        val workInfos = workManager.getWorkInfosForUniqueWork(RuntimeRestoreWorker.UNIQUE_WORK_NAME).get()
        assertEquals(0, workInfos.size)
    }

    @Test
    fun userUnlockedKeepsExactlyOneRestoreRequest() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .build(),
        )
        val workManager = WorkManager.getInstance(context)
        workManager.cancelAllWork()
        waitForWorkIdle(workManager)
        workManager.pruneWork()

        val receiver = BootReceiver()
        receiver.onReceive(context, Intent(Intent.ACTION_USER_UNLOCKED))
        receiver.onReceive(context, Intent(Intent.ACTION_USER_UNLOCKED))

        val workInfos = workManager.getWorkInfosForUniqueWork(RuntimeRestoreWorker.UNIQUE_WORK_NAME).get()
        assertEquals(1, workInfos.size)
        assertTrue(workInfos.single().state != WorkInfo.State.CANCELLED)
    }

    @Test
    fun bootCompletedKeepsExactlyOneRestoreRequest() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .build(),
        )
        val workManager = WorkManager.getInstance(context)
        workManager.cancelAllWork()
        waitForWorkIdle(workManager)
        workManager.pruneWork()

        val receiver = BootReceiver()
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        val workInfos = workManager.getWorkInfosForUniqueWork(RuntimeRestoreWorker.UNIQUE_WORK_NAME).get()
        assertEquals(1, workInfos.size)
        assertTrue(workInfos.single().state != WorkInfo.State.CANCELLED)
    }

    @Test
    fun restoreWorkerRestoresDependenciesBeforeStartingForegroundServiceOnce() = runBlocking {
        val previousCoordinator = BootRecoveryDependencies.coordinator
        val previousRestorer = BootRecoveryDependencies.restorer
        val events = mutableListOf<String>()
        val coordinator = RecordingRestoreCoordinator(events)
        var starts = 0
        BootRecoveryDependencies.coordinator = coordinator
        BootRecoveryDependencies.restorer = BackgroundRuntimeRestorer { coordinator.restore() }

        try {
            RuntimeRestoreWorker.restoreBackgroundRuntime(
                context,
                startBackgroundRuntime = {
                starts += 1
                events += "start"
                },
            )

            assertEquals(listOf("restore", "start"), events)
            assertEquals(1, coordinator.restores)
            assertEquals(1, starts)
        } finally {
            BootRecoveryDependencies.coordinator = previousCoordinator
            BootRecoveryDependencies.restorer = previousRestorer
        }
    }

    @Test
    fun restoreWorkerRetriesBeforeReturningFailure() = runBlocking {
        val previousRestorer = BootRecoveryDependencies.restorer
        BootRecoveryDependencies.restorer = BackgroundRuntimeRestorer {
                error("restore failed")
        }

        try {
            val retryWorker = TestListenableWorkerBuilder<RuntimeRestoreWorker>(context)
                .setRunAttemptCount(0)
                .build()
            val retryResult = retryWorker.doWork()
            assertEquals(androidx.work.ListenableWorker.Result.retry().javaClass, retryResult.javaClass)

            val failureWorker = TestListenableWorkerBuilder<RuntimeRestoreWorker>(context)
                .setRunAttemptCount(3)
                .build()
            val failureResult = failureWorker.doWork()
            assertEquals(androidx.work.ListenableWorker.Result.failure().javaClass, failureResult.javaClass)
        } finally {
            BootRecoveryDependencies.restorer = previousRestorer
        }
    }

    @Test
    fun assistantScreenShowsBatteryGuidanceForReliableTelegramPolling() {
        compose.setContent {
            OpenAssistantScreen(
                onRequestAssistantRole = {},
                onRequestCapabilityPermissions = {},
            )
        }

        compose.onNodeWithText("Allow background activity").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Disable battery optimization for Dark Lord if you want reliable Telegram polling.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Keep notifications enabled.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Open battery settings").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun batterySettingsIntentRequestsOptimizationExemptionWhenAvailable() {
        val intent = BackgroundRuntimeSettings.intent(
            contextPackageName = context.packageName,
            canRequestIgnoreBatteryOptimizations = true,
        )

        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, intent.action)
        assertEquals("package:${context.packageName}", intent.dataString)
    }

    @Test
    fun batterySettingsIntentFallsBackToAppDetailsWhenRequestActionUnavailable() {
        val intent = BackgroundRuntimeSettings.intent(
            contextPackageName = context.packageName,
            canRequestIgnoreBatteryOptimizations = false,
        )

        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:${context.packageName}", intent.dataString)
    }

    private fun waitForWorkIdle(workManager: WorkManager) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            val active = workManager.getWorkInfosForUniqueWork(RuntimeRestoreWorker.UNIQUE_WORK_NAME).get()
                .any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED }
            if (!active) return
            SystemClock.sleep(50)
        }
    }
}

private class RecordingRestoreCoordinator(
    private val events: MutableList<String>,
) : AgentRuntimeRecovery {
    var restores = 0
    override val isRunning: Boolean = false

    override fun start() {
        events += "start"
    }

    override suspend fun stop() = Unit

    override suspend fun restore() {
        restores += 1
        events += "restore"
    }
}
