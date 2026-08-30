package com.fsaint.androidagent

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class RuntimeRestoreWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return runCatching { BootRecoveryDependencies.coordinator.restore(); Result.success() }
            .getOrElse { if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure() }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "dark-lord-runtime-restore"
        private const val MAX_RETRIES = 3
    }
}
