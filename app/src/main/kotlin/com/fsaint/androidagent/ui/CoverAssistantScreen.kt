package com.fsaint.androidagent.ui

import androidx.compose.runtime.Composable
import com.fsaint.androidagent.voice.VoiceTurnState

/** Compact assistant surface for the folded cover display. */
@Composable
fun CoverAssistantScreen(state: VoiceTurnState = VoiceTurnState.Idle, onTap: () -> Unit = {}) =
    AssistantSurface(state, onTap, compact = true)
