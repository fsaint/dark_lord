package com.fsaint.androidagent

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Prevents the persistent runtime from operating without a user-visible notification. */
internal class BackgroundRuntimeNotificationGate(
    private val context: Context,
) {
    fun canShowRuntimeNotification(): Boolean {
        val notifications = context.getSystemService(NotificationManager::class.java)
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val channelImportance = notifications
            .getNotificationChannel(AgentRuntimeService.CHANNEL_ID)
            ?.importance
        return shouldRunBackgroundRuntime(
            sdkInt = Build.VERSION.SDK_INT,
            permissionGranted = permissionGranted,
            appNotificationsEnabled = notifications.areNotificationsEnabled(),
            channelImportance = channelImportance,
        )
    }
}

internal fun shouldRunBackgroundRuntime(
    sdkInt: Int,
    permissionGranted: Boolean,
    appNotificationsEnabled: Boolean,
    channelImportance: Int?,
): Boolean =
    (sdkInt < Build.VERSION_CODES.TIRAMISU || permissionGranted) &&
        appNotificationsEnabled &&
        channelImportance != NotificationManager.IMPORTANCE_NONE
