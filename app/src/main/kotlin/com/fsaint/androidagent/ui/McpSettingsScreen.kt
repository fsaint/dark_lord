package com.fsaint.androidagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
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
import com.fsaint.androidagent.mcp.McpConnectionState

data class McpServerDraft(val name: String, val endpoint: String, val oauthTokenEndpoint: String, val clientId: String)

@Composable
fun McpSettingsScreen(
    configurations: List<McpConfigurationEntity>,
    onAdd: suspend (McpServerDraft) -> Result<Unit>,
    onDelete: suspend (String) -> Unit,
    onBack: () -> Unit,
    connectionStates: Map<String, McpConnectionState> = emptyMap(),
    onRefresh: suspend (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }
    var tokenEndpoint by remember { mutableStateOf("") }
    var clientId by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    DarkLordTheme {
        Column(Modifier.widthIn(max = 840.dp).fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("MCP servers", style = MaterialTheme.typography.headlineSmall)
            Text("Connect no-auth HTTPS Streamable HTTP MCP servers. The owner can use discovered tools; other principals need an MCP grant. Saving a server does not mean it is connected.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Add server", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(name, { name = it }, label = { Text("Display name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(endpoint, { endpoint = it }, label = { Text("HTTPS endpoint") }, supportingText = { Text("Example: https://mcp.example.com/mcp") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(tokenEndpoint, { tokenEndpoint = it }, label = { Text("OAuth token endpoint (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(clientId, { clientId = it }, label = { Text("OAuth client ID (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Text("OAuth fields are retained for future sign-in support. Authentication is not implemented yet.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = {
                        scope.launch {
                            onAdd(McpServerDraft(name.trim(), endpoint.trim(), tokenEndpoint.trim(), clientId.trim())).onSuccess {
                                name = ""; endpoint = ""; tokenEndpoint = ""; clientId = ""; message = "Server saved. Checking its tools…"
                            }.onFailure { message = it.message ?: "Could not save server." }
                        }
                    }, modifier = Modifier.fillMaxWidth(), enabled = name.isNotBlank() && endpoint.startsWith("https://")) { Text("Save MCP server") }
                }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            configurations.forEach { config ->
                val endpointText = config.configuration.toString(Charsets.UTF_8).substringBefore('\u0000').substringBefore('?')
                val status = connectionStates[config.id] ?: McpConnectionState()
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(config.name, style = MaterialTheme.typography.titleMedium)
                        Text(endpointText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(status.message, color = if (status.status in setOf("READY", "SAVED", "CONNECTING", "NO_TOOLS")) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                        status.checkedAt?.let { Text("Last checked: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it))}", style = MaterialTheme.typography.bodySmall) }
                        if (status.toolNames.isNotEmpty()) Text("Tools (${status.toolNames.size}):\n" + status.toolNames.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled = status.status != "CONNECTING", onClick = { scope.launch { runCatching { onRefresh(config.id) }.onFailure { message = "Could not refresh the server." } } }) { Text("Refresh") }
                            OutlinedButton(onClick = { scope.launch { runCatching { onDelete(config.id) }.onFailure { message = "Could not remove the server." } } }) { Text("Remove") }
                        }
                    }
                }
            }
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
        }
    }
}

fun encodeMcpDraft(draft: McpServerDraft): ByteArray = "${draft.endpoint}\u0000${draft.oauthTokenEndpoint}\u0000${draft.clientId}".toByteArray()
