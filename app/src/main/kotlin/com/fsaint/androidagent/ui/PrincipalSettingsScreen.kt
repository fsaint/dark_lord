package com.fsaint.androidagent.ui

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fsaint.androidagent.model.PrincipalRole
import com.fsaint.androidagent.policy.Principal
import com.fsaint.androidagent.policy.PrincipalDirectory
import kotlinx.coroutines.launch

data class CommunicationsAccessStatus(
    val smsRoleHeld: Boolean,
    val dialerRoleHeld: Boolean,
    val notificationListenerEnabled: Boolean,
    val postNotificationsPermissionGranted: Boolean,
    val capabilityPermissionsGranted: Boolean,
)

@Composable
fun PrincipalSettingsScreen(
    principals: PrincipalDirectory,
    owner: Principal?,
    onProvisionOwner: suspend (String) -> Result<Principal>,
    accessStatus: () -> CommunicationsAccessStatus,
    onRequestRoles: () -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenNotificationListenerSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var directory by remember { mutableStateOf(emptyList<Principal>()) }
    var phoneNumber by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf(accessStatus()) }
    var currentOwner by remember(owner) { mutableStateOf(owner) }
    var ownerPhoneNumber by remember { mutableStateOf("") }
    var ownerConfirmed by remember { mutableStateOf(false) }
    var ownerProvisioning by remember { mutableStateOf(false) }
    var ownerError by remember { mutableStateOf<String?>(null) }
    var ownerSuccess by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reload) {
        directory = principals.list()
        status = accessStatus()
    }

    MaterialTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Communications administration", style = MaterialTheme.typography.headlineSmall)
            Text("SMS default app: ${status.smsRoleHeld.asAccessLabel()}")
            Text("Dialer default app: ${status.dialerRoleHeld.asAccessLabel()}")
            Text("Notification access: ${status.notificationListenerEnabled.asAccessLabel()}")
            Text("Notification permission: ${status.postNotificationsPermissionGranted.asAccessLabel()}")
            Text("SMS and call permissions: ${status.capabilityPermissionsGranted.asAccessLabel()}")
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onRequestRoles,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Request SMS and dialer roles") }
                Button(
                    onClick = onRequestPermissions,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Grant SMS and call permissions") }
            }
            Button(
                onClick = onOpenNotificationListenerSettings,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Open notification access settings") }
            Button(
                onClick = { reload++ },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Refresh access status") }

            if (currentOwner == null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Set up first owner", style = MaterialTheme.typography.titleMedium)
                        Text("This one-time action makes this number the sole owner. The owner cannot be replaced here later.")
                        OutlinedTextField(
                            value = ownerPhoneNumber,
                            onValueChange = {
                                ownerPhoneNumber = it
                                ownerError = null
                            },
                            label = { Text("Owner E.164 number") },
                            supportingText = { Text("Include + and country code, such as +14155550100") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !ownerProvisioning,
                            singleLine = true,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { role = Role.Checkbox }
                                .toggleable(
                                    value = ownerConfirmed,
                                    enabled = !ownerProvisioning,
                                    role = Role.Checkbox,
                                    onValueChange = {
                                        ownerConfirmed = it
                                        ownerError = null
                                    },
                                ),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Checkbox(
                                checked = ownerConfirmed,
                                onCheckedChange = null,
                                enabled = !ownerProvisioning,
                            )
                            Text("I confirm this is my phone number", modifier = Modifier.padding(top = 12.dp))
                        }
                        Button(
                            onClick = {
                                val e164 = ownerPhoneNumber.trim()
                                if (!E164.matches(e164)) {
                                    ownerError = "Enter a valid E.164 number."
                                    return@Button
                                }
                                ownerProvisioning = true
                                ownerError = null
                                scope.launch {
                                    onProvisionOwner(e164)
                                        .onSuccess { provisionedOwner ->
                                            currentOwner = provisionedOwner
                                            ownerPhoneNumber = ""
                                            ownerConfirmed = false
                                            ownerSuccess = "Owner provisioned successfully."
                                            reload++
                                        }
                                        .onFailure { failure ->
                                            ownerError = failure.message ?: "Owner provisioning failed."
                                        }
                                    ownerProvisioning = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = ownerConfirmed && !ownerProvisioning,
                        ) { Text(if (ownerProvisioning) "Provisioning owner…" else "Provision owner") }
                        ownerError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
            } else {
                Text(
                    "Owner: ${currentOwner?.e164 ?: currentOwner?.id}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                ownerSuccess?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            }

            Text("Known principals", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            OutlinedTextField(
                value = phoneNumber,
                onValueChange = { phoneNumber = it },
                label = { Text("E.164 number") },
                supportingText = { Text("Use a number such as +14155550100") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Button(onClick = {
                val e164 = phoneNumber.trim()
                if (!E164.matches(e164)) {
                    error = "Enter a valid E.164 number."
                    return@Button
                }
                scope.launch {
                    if (principals.owner()?.e164 == e164) {
                        error = "The owner number cannot also be known."
                        return@launch
                    }
                    runCatching {
                        principals.upsert(Principal("known:$e164", e164, PrincipalRole.KNOWN))
                    }.onFailure {
                        error = "That number is already assigned to a principal."
                    }.onSuccess {
                        phoneNumber = ""
                        error = null
                        reload++
                    }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Add known principal") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            directory.forEach { principal ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${principal.role}: ${principal.e164 ?: principal.id}")
                    val e164 = principal.e164
                    if (principal.role == PrincipalRole.KNOWN && e164 != null) {
                        Button(onClick = {
                            scope.launch {
                                principals.removeKnown(e164)
                                reload++
                            }
                        }) { Text("Remove") }
                    }
                }
            }
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
        }
    }
}

private fun Boolean.asAccessLabel(): String = if (this) "Granted" else "Required"
private val E164 = Regex("\\+[1-9]\\d{1,14}")
