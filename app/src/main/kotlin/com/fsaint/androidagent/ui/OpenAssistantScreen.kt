package com.fsaint.androidagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenAssistantScreen(
    onRequestAssistantRole: () -> Unit,
    onRequestCapabilityPermissions: () -> Unit,
    onRequestScreenCapture: (() -> Unit)? = null,
    onOpenPrincipalSettings: () -> Unit = {},
    onOpenNotificationListenerSettings: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    onSaveOpenAiKey: (String) -> Unit = {},
    onSaveTelegramToken: (String) -> Unit = {},
    onOpenMcpSettings: () -> Unit = {},
) {
    var apiKey by remember { mutableStateOf("") }
    var telegramToken by remember { mutableStateOf("") }
    DarkLordTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("Dark Lord") }) },
        ) { insets ->
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()
                    .padding(insets).padding(horizontal = 20.dp, vertical = 16.dp).widthIn(max = 640.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                Text("Your private phone agent", style = MaterialTheme.typography.headlineSmall)
                Text("Invoke Dark Lord from the Side button, SMS, or voice. Actions stay inside the permissions you grant.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Model connection", style = MaterialTheme.typography.titleLarge)
                        Text("Set up an owner-only OpenAI key. It is encrypted locally and never included in diagnostics or messages.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = { Text("OpenAI API key") },
                            placeholder = { Text("sk-…") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Button(onClick = { onSaveOpenAiKey(apiKey); apiKey = "" }, modifier = Modifier.fillMaxWidth(), enabled = apiKey.isNotBlank()) { Text("Save model key") }
                        OutlinedTextField(
                            value = telegramToken,
                            onValueChange = { telegramToken = it },
                            label = { Text("Telegram bot token") },
                            placeholder = { Text("123456:…") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedButton(
                            onClick = { onSaveTelegramToken(telegramToken); telegramToken = "" },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = telegramToken.isNotBlank(),
                        ) { Text("Save Telegram bot token") }
                    }
                }
                HorizontalDivider()
                Text("Device access", style = MaterialTheme.typography.titleLarge)
                Button(onClick = onRequestAssistantRole, modifier = Modifier.fillMaxWidth()) { Text("Make Dark Lord your Assistant") }
                OutlinedButton(onClick = onRequestCapabilityPermissions, modifier = Modifier.fillMaxWidth()) { Text("Grant microphone, camera, and SMS") }
                OutlinedButton(onClick = onOpenNotificationListenerSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Allow Dark Lord to read notifications")
                }
                if (onRequestScreenCapture != null) {
                    OutlinedButton(onClick = onRequestScreenCapture, modifier = Modifier.fillMaxWidth()) { Text("Allow one screen capture") }
                }
                Text("Administration", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 4.dp))
                OutlinedButton(onClick = onOpenPrincipalSettings, modifier = Modifier.fillMaxWidth()) { Text("Communications settings") }
                OutlinedButton(onClick = onOpenMcpSettings, modifier = Modifier.fillMaxWidth()) { Text("MCP server settings") }
                OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors()) { Text("Diagnostics") }
            }
        }
    }
}
