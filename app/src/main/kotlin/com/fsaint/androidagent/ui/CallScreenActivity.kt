package com.fsaint.androidagent.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fsaint.androidagent.capabilities.telephony.ActiveCall
import com.fsaint.androidagent.capabilities.telephony.AgentInCallServiceDependencies
import com.fsaint.androidagent.capabilities.telephony.CallRepository
import com.fsaint.androidagent.model.ToolResult

class CallScreenActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val callId = intent.getStringExtra(EXTRA_CALL_ID).orEmpty()
        val repository = AgentInCallServiceDependencies.callRepository()
        setContent {
            MaterialTheme {
                val calls by repository.calls.collectAsState()
                CallScreen(
                    call = calls.firstOrNull { it.id == callId },
                    repository = repository,
                )
            }
        }
    }

    companion object {
        const val EXTRA_CALL_ID = "com.fsaint.androidagent.extra.CALL_ID"
    }
}

@Composable
internal fun CallScreen(
    call: ActiveCall?,
    repository: CallRepository,
) {
    var actionStatus by remember(call?.id) { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (call == null) {
            Text("Call ended", style = MaterialTheme.typography.headlineMedium)
            return@Column
        }

        Text(call.telephoneHandle ?: "Unknown caller", style = MaterialTheme.typography.headlineMedium)
        Text(callStateLabel(call.state))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (call.canAnswer) {
                Button(onClick = { actionStatus = repository.answer(call.id).status("Answer") }) {
                    Text("Answer")
                }
            }
            if (call.canReject) {
                Button(onClick = { actionStatus = repository.reject(call.id).status("Reject") }) {
                    Text("Reject")
                }
            }
            if (call.canDisconnect) {
                Button(onClick = { actionStatus = repository.disconnect(call.id).status("Disconnect") }) {
                    Text("Disconnect")
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (call.canMute) {
                val muted = call.muted
                Button(onClick = {
                    actionStatus = repository.setMuted(call.id, !muted).status(if (muted) "Unmute" else "Mute")
                }) {
                    Text(if (muted) "Unmute" else "Mute")
                }
            }
            if (call.canHold) {
                Button(onClick = { actionStatus = repository.hold(call.id).status("Hold") }) {
                    Text("Hold")
                }
            }
            if (call.canUnhold) {
                Button(onClick = { actionStatus = repository.unhold(call.id).status("Resume") }) {
                    Text("Resume")
                }
            }
        }

        actionStatus?.let { Text(it) }
    }
}

private fun ToolResult<Unit>.status(action: String): String =
    if (success) "$action requested" else "$action unavailable: ${error?.name ?: "UNKNOWN"}"

private fun callStateLabel(state: Int): String = when (state) {
    android.telecom.Call.STATE_NEW -> "Starting"
    android.telecom.Call.STATE_CONNECTING -> "Connecting"
    android.telecom.Call.STATE_DIALING -> "Dialing"
    android.telecom.Call.STATE_RINGING -> "Incoming call"
    android.telecom.Call.STATE_ACTIVE -> "Call in progress"
    android.telecom.Call.STATE_HOLDING -> "On hold"
    android.telecom.Call.STATE_DISCONNECTING -> "Ending"
    android.telecom.Call.STATE_DISCONNECTED -> "Call ended"
    else -> "Call state unavailable"
}
