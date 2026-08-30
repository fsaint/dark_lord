package com.fsaint.androidagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun OpenAssistantScreen(
    onRequestAssistantRole: () -> Unit,
    onRequestCapabilityPermissions: () -> Unit,
    onRequestScreenCapture: (() -> Unit)? = null,
    onOpenPrincipalSettings: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
) {
    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Dark Lord", style = MaterialTheme.typography.headlineMedium)
            Text("Grant Assistant access to use the supported system invocation.")
            Button(onClick = onRequestAssistantRole, modifier = Modifier.padding(top = 16.dp)) {
                Text("Make Dark Lord your Assistant")
            }
            Button(onClick = onRequestCapabilityPermissions, modifier = Modifier.padding(top = 8.dp)) {
                Text("Grant microphone, camera, and SMS")
            }
            if (onRequestScreenCapture != null) {
                Button(onClick = onRequestScreenCapture, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Allow one screen capture")
                }
            }
            Button(onClick = onOpenPrincipalSettings, modifier = Modifier.padding(top = 8.dp)) {
                Text("Communications settings")
            }
            Button(onClick = onOpenDiagnostics, modifier = Modifier.padding(top = 8.dp)) {
                Text("Diagnostics")
            }
        }
    }
}
