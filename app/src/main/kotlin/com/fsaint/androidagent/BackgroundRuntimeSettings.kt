package com.fsaint.androidagent

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

object BackgroundRuntimeSettings {
    val guidance = listOf(
        "Allow background activity",
        "Disable battery optimization for Dark Lord if you want reliable Telegram polling.",
        "Keep notifications enabled.",
    )

    fun intent(contextPackageName: String, canRequestIgnoreBatteryOptimizations: Boolean): Intent {
        val packageUri = Uri.parse("package:$contextPackageName")
        return if (canRequestIgnoreBatteryOptimizations) {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
        }
    }

    fun intent(packageManager: PackageManager, packageName: String): Intent {
        val requestIntent = intent(packageName, canRequestIgnoreBatteryOptimizations = true)
        return if (requestIntent.resolveActivity(packageManager) != null) {
            requestIntent
        } else {
            intent(packageName, canRequestIgnoreBatteryOptimizations = false)
        }
    }
}

@Composable
fun BackgroundRuntimeSettingsCard(onOpenSettings: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Background runtime", style = MaterialTheme.typography.titleLarge)
            BackgroundRuntimeSettings.guidance.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Open battery settings")
            }
        }
    }
}
