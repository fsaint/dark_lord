package com.fsaint.androidagent

import android.app.role.RoleManager
import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.fsaint.androidagent.ui.OpenAssistantScreen

class MainActivity : ComponentActivity() {
    private val requestAssistantRole = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { }

    private val requestCapabilityPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OpenAssistantScreen(
                onRequestAssistantRole = ::requestAssistantRole,
                onRequestCapabilityPermissions = ::requestCapabilityPermissions,
            )
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
                Manifest.permission.SEND_SMS,
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        requestCapabilityPermissions.launch(permissions.toTypedArray())
    }

}
