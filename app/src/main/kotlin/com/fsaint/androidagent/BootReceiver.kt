package com.fsaint.androidagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

fun interface BackgroundRuntimeRestoreScheduler {
    fun enqueue(context: Context)
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_USER_UNLOCKED -> scheduler.enqueue(context)
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            null -> return
            else -> return
        }
    }

    companion object {
        @Volatile
        internal var scheduler: BackgroundRuntimeRestoreScheduler = defaultScheduler()

        private fun defaultScheduler(): BackgroundRuntimeRestoreScheduler =
            BackgroundRuntimeRestoreScheduler { context ->
                WorkManager.getInstance(context).enqueueUniqueWork(
                    RuntimeRestoreWorker.UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<RuntimeRestoreWorker>().build(),
                )
            }
    }
}
