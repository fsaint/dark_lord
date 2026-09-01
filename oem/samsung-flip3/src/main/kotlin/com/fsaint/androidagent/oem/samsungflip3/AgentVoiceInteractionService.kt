package com.fsaint.androidagent.oem.samsungflip3

import android.service.voice.VoiceInteractionService

/**
 * Supported Assistant-role entry point. The OS, not this service, owns Side-key handling: holding the key
 * shows the session (push-to-talk "press"); the release is never reported, so the session's hide, the
 * recognizer's end of speech, or a tap on the surface stands in for it.
 */
class AgentVoiceInteractionService : VoiceInteractionService()
