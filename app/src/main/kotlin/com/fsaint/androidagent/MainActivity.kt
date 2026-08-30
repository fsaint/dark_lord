package com.fsaint.androidagent

import android.app.role.RoleManager
import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.fsaint.androidagent.ui.OpenAssistantScreen
import com.fsaint.androidagent.ui.PrincipalSettingsScreen

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var principalSettingsOpen by rememberSaveable { mutableStateOf(false) }
            if (principalSettingsOpen) {
                PrincipalSettingsScreen(
                    principals = (application as DarkLordApplication).principals,
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
                    onOpenPrincipalSettings = { principalSettingsOpen = true },
                )
            }
        }
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
