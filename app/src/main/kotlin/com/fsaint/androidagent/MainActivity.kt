package com.fsaint.androidagent

import android.app.role.RoleManager
import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.fsaint.androidagent.policy.Principal
import com.fsaint.androidagent.ui.CommunicationsAccessStatus
import com.fsaint.androidagent.ui.OpenAssistantScreen
import com.fsaint.androidagent.ui.PrincipalSettingsScreen
import com.fsaint.androidagent.ui.DebugScreen
import com.fsaint.androidagent.ui.McpSettingsScreen
import com.fsaint.androidagent.data.McpConfigurationEntity
import com.fsaint.androidagent.runtime.CredentialOutcome

class MainActivity : ComponentActivity() {
    private val requestAssistantRole = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { }

    private val requestCapabilityPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    private val requestSmsRole = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { requestDialerRoleIfNeeded() }

    private val requestDialerRole = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { }

    private val requestScreenCapture = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        (application as DarkLordApplication).acceptScreenCaptureGrant(result.resultCode, result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            var principalSettingsOpen by rememberSaveable { mutableStateOf(false) }
            var diagnosticsOpen by rememberSaveable { mutableStateOf(false) }
            var mcpSettingsOpen by rememberSaveable { mutableStateOf(false) }
            if (mcpSettingsOpen) {
                McpSettingsRoute(application as DarkLordApplication) { mcpSettingsOpen = false }
            } else if (diagnosticsOpen) {
                DebugScreen((application as DarkLordApplication).diagnostics) { diagnosticsOpen = false }
            } else if (principalSettingsOpen) {
                PrincipalSettingsRoute(
                    application = application as DarkLordApplication,
                    accessStatus = ::communicationsAccessStatus,
                    onRequestRoles = ::requestCommunicationsRoles,
                    onRequestPermissions = ::requestCapabilityPermissions,
                    onOpenNotificationListenerSettings = ::openNotificationListenerSettings,
                    onBack = { principalSettingsOpen = false },
                )
            } else {
                OpenAssistantScreen(
                    onRequestAssistantRole = ::requestAssistantRole,
                    onRequestCapabilityPermissions = ::requestCapabilityPermissions,
                    onRequestScreenCapture = ::requestScreenCapture,
                    onOpenPrincipalSettings = { principalSettingsOpen = true },
                    onOpenNotificationListenerSettings = ::openNotificationListenerSettings,
                    onOpenDiagnostics = { diagnosticsOpen = true },
                    onOpenMcpSettings = { mcpSettingsOpen = true },
                    onSaveOpenAiKey = { value ->
                        lifecycleScope.launch {
                            val outcome = (application as DarkLordApplication).saveOpenAiApiKey(value)
                            val message = when (outcome) {
                                CredentialOutcome.SAVED -> "OpenAI API key saved."
                                CredentialOutcome.DENIED -> "Key rejected. Use an owner account and an sk- key."
                                CredentialOutcome.FAILED -> "Could not save the API key."
                            }
                            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                        }
                    },
                    onSaveTelegramToken = { value ->
                        lifecycleScope.launch {
                            val outcome = (application as DarkLordApplication).saveTelegramBotToken(value)
                            val message = when (outcome) {
                                CredentialOutcome.SAVED -> "Telegram bot token saved."
                                CredentialOutcome.DENIED -> "Token rejected. Use an owner account and a valid Telegram bot token."
                                CredentialOutcome.FAILED -> "Could not save the Telegram bot token."
                            }
                            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                        }
                    },
                    onSaveTelegramOwnerChatId = { value ->
                        val saved = (application as DarkLordApplication).saveTelegramOwnerChatId(value)
                        Toast.makeText(this@MainActivity, if (saved) "Telegram owner ID saved." else "Enter a numeric Telegram chat ID.", Toast.LENGTH_LONG).show()
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as DarkLordApplication).startBackgroundRuntime()
    }

    private fun requestAssistantRole() {
        val roles = getSystemService(RoleManager::class.java)
        if (roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && !roles.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
            requestAssistantRole.launch(roles.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
        }
    }

    private fun requestCapabilityPermissions() {
        val permissions = mutableListOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
                Manifest.permission.READ_SMS,
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.SEND_SMS,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.CALL_PHONE,
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        requestCapabilityPermissions.launch(permissions.toTypedArray())
    }

    private fun requestScreenCapture() {
        requestScreenCapture.launch((application as DarkLordApplication).createScreenCaptureConsentIntent())
    }

    fun requestCommunicationsRoles() {
        val roles = getSystemService(RoleManager::class.java)
        if (roles.isRoleAvailable(RoleManager.ROLE_SMS) && !roles.isRoleHeld(RoleManager.ROLE_SMS)) {
            requestSmsRole.launch(roles.createRequestRoleIntent(RoleManager.ROLE_SMS))
        } else {
            requestDialerRoleIfNeeded()
        }
    }

    fun openNotificationListenerSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun requestDialerRoleIfNeeded() {
        val roles = getSystemService(RoleManager::class.java)
        if (roles.isRoleAvailable(RoleManager.ROLE_DIALER) && !roles.isRoleHeld(RoleManager.ROLE_DIALER)) {
            requestDialerRole.launch(roles.createRequestRoleIntent(RoleManager.ROLE_DIALER))
        }
    }

    private fun communicationsAccessStatus() = (application as DarkLordApplication).communicationsAccessStatus()
}

@Composable
private fun PrincipalSettingsRoute(
    application: DarkLordApplication,
    accessStatus: () -> CommunicationsAccessStatus,
    onRequestRoles: () -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenNotificationListenerSettings: () -> Unit,
    onBack: () -> Unit,
) {
    var owner by remember { mutableStateOf<Principal?>(null) }
    var ownerLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        owner = application.principals.owner()
        ownerLoaded = true
    }

    if (!ownerLoaded) {
        MaterialTheme { Text("Loading communications administration…") }
        return
    }

    PrincipalSettingsScreen(
        principals = application.principals,
        owner = owner,
        onProvisionOwner = { e164 ->
            application.ownerProvisioning.provision(e164).also { result ->
                result.onSuccess { owner = it }
            }
        },
        accessStatus = accessStatus,
        onRequestRoles = onRequestRoles,
        onRequestPermissions = onRequestPermissions,
        onOpenNotificationListenerSettings = onOpenNotificationListenerSettings,
        onBack = onBack,
    )
}

@Composable
private fun McpSettingsRoute(application: DarkLordApplication, onBack: () -> Unit) {
    var configurations by remember { mutableStateOf<List<McpConfigurationEntity>>(emptyList()) }
    LaunchedEffect(Unit) { configurations = application.mcpConfigurations() }
    McpSettingsScreen(
        configurations = configurations,
        onAdd = { draft -> application.addMcpServer(draft).also { if (it.isSuccess) configurations = application.mcpConfigurations() } },
        onDelete = { id -> application.removeMcpServer(id); configurations = application.mcpConfigurations() },
        onBack = onBack,
    )
}
