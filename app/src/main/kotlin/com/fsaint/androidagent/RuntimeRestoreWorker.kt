package com.fsaint.androidagent

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class RuntimeRestoreWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return runCatching { restoreBackgroundRuntime(applicationContext); Result.success() }
            .getOrElse { if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure() }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "dark-lord-runtime-restore"
        private const val MAX_RETRIES = 3

        internal suspend fun restoreBackgroundRuntime(
            context: Context,
            notificationsAvailable: () -> Boolean = {
                BackgroundRuntimeNotificationGate(context).canShowRuntimeNotification()
            },
            restoreDependencies: suspend () -> Unit = { BootRecoveryDependencies.restorer.restore() },
            startBackgroundRuntime: () -> Unit = {
                val application = context.applicationContext as? DarkLordApplication
                if (application != null) {
                    application.startBackgroundRuntime()
                } else {
                    BootRecoveryDependencies.foregroundStarter.start(context)
                }
            },
        ) {
            if (!notificationsAvailable()) return
            restoreDependencies()
            if (!BootRecoveryDependencies.coordinator.isRunning) {
                startBackgroundRuntime()
            }
        }
    }
}
