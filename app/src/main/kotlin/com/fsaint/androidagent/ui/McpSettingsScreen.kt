package com.fsaint.androidagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fsaint.androidagent.data.McpConfigurationEntity
import kotlinx.coroutines.launch

data class McpServerDraft(val name: String, val endpoint: String, val oauthTokenEndpoint: String, val clientId: String)

@Composable
fun McpSettingsScreen(
    configurations: List<McpConfigurationEntity>,
    onAdd: suspend (McpServerDraft) -> Result<Unit>,
    onDelete: suspend (String) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }
    var tokenEndpoint by remember { mutableStateOf("") }
    var clientId by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    DarkLordTheme {
        Column(Modifier.fillMaxSize().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("MCP servers", style = MaterialTheme.typography.headlineSmall)
            Text("Connect external HTTPS MCP servers. Each server is stored locally and must be granted to a principal before use.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Add server", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(name, { name = it }, label = { Text("Display name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(endpoint, { endpoint = it }, label = { Text("HTTPS endpoint") }, supportingText = { Text("Example: https://mcp.example.com/mcp") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(tokenEndpoint, { tokenEndpoint = it }, label = { Text("OAuth token endpoint (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(clientId, { clientId = it }, label = { Text("OAuth client ID (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Button(onClick = {
                        scope.launch {
                            onAdd(McpServerDraft(name.trim(), endpoint.trim(), tokenEndpoint.trim(), clientId.trim())).onSuccess {
                                name = ""; endpoint = ""; tokenEndpoint = ""; clientId = ""; message = "Server saved. Grant its scope before using it."
                            }.onFailure { message = it.message ?: "Could not save server." }
                        }
                    }, modifier = Modifier.fillMaxWidth(), enabled = name.isNotBlank() && endpoint.startsWith("https://")) { Text("Save MCP server") }
                }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            configurations.forEach { config ->
                val endpointText = config.configuration.toString(Charsets.UTF_8).substringBefore('\u0000')
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) { Text(config.name, style = MaterialTheme.typography.titleMedium); Text(endpointText, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    OutlinedButton(onClick = { scope.launch { onDelete(config.id) } }) { Text("Remove") }
                }
            }
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
        }
    }
}

fun encodeMcpDraft(draft: McpServerDraft): ByteArray = "${draft.endpoint}\u0000${draft.oauthTokenEndpoint}\u0000${draft.clientId}".toByteArray()
