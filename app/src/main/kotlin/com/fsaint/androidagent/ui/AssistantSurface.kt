package com.fsaint.androidagent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fsaint.androidagent.voice.VoiceTurnState

/**
 * The assistant session surface. [compact] fits the 512x260 cover display; the full variant scrolls long
 * replies. Tapping while listening sends the utterance early.
 */
@Composable
fun AssistantSurface(state: VoiceTurnState, onTap: () -> Unit, compact: Boolean) {
    DarkLordTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clickable(enabled = state == VoiceTurnState.Listening, onClick = onTap)
                .padding(if (compact) 8.dp else 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Dark Lord", style = MaterialTheme.typography.titleMedium)
            if (compact) {
                Text(
                    assistantLabel(state),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    assistantLabel(state),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                )
                if (state == VoiceTurnState.Listening) {
                    Text("Tap to send", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
fun OpenAssistantSurface(state: VoiceTurnState, onTap: () -> Unit) = AssistantSurface(state, onTap, compact = false)

internal fun assistantLabel(state: VoiceTurnState): String = when (state) {
    VoiceTurnState.Idle -> "Hold the side key and speak"
    VoiceTurnState.Listening -> "Listening…"
    VoiceTurnState.Finalizing -> "…"
    VoiceTurnState.Thinking -> "Thinking…"
    is VoiceTurnState.Responding -> state.text
    is VoiceTurnState.Error -> state.reason.spokenMessage
}
