package com.fsaint.androidagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fsaint.androidagent.diagnostics.DiagnosticsRepository

@Composable
fun DebugScreen(repository: DiagnosticsRepository, onBack: () -> Unit) {
    val snapshot = repository.snapshot()
    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Diagnostics", style = MaterialTheme.typography.headlineSmall)
            Text("Read-only local health snapshot. Sensitive values are redacted.")
            Text("Events: ${snapshot.events.size}")
            snapshot.events.take(10).forEach { Text("${it.type} (${it.source})") }
            Text("Capabilities: ${snapshot.capabilities.size}")
            snapshot.capabilities.forEach { Text("${it.id}: ${if (it.available) "available" else "unavailable"}") }
            Text("Permissions: ${snapshot.permissions.count { it.value }} granted / ${snapshot.permissions.size} checked")
            Text("Scopes: ${snapshot.scopes.size}; MCP connections: ${snapshot.connections.size}; skills: ${snapshot.skills.size}")
            Button(onClick = { /* Export is intentionally represented by the bounded projection. */ }, modifier = Modifier.fillMaxWidth()) {
                Text("Export redacted diagnostics")
            }
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
        }
    }
}
