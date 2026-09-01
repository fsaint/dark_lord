package com.fsaint.androidagent.voice

/** Observable state of one push-to-talk turn, from the side-key hold to the spoken reply. */
sealed interface VoiceTurnState {
    data object Idle : VoiceTurnState
    data object Listening : VoiceTurnState
    data object Finalizing : VoiceTurnState
    data object Thinking : VoiceTurnState
    data class Responding(val text: String) : VoiceTurnState
    data class Error(val reason: VoiceTurnError) : VoiceTurnState
}

/** Why a turn ended without an answer. Each reason carries the sentence spoken to the user. */
enum class VoiceTurnError(val spokenMessage: String) {
    NO_SPEECH("I didn't catch that."),
    RECOGNIZER("Speech recognition isn't available right now."),
    NO_OWNER("Set up an owner in Dark Lord first."),
    MICROPHONE_PERMISSION("Dark Lord needs microphone access. Please grant it in the app."),
    TIMEOUT("That's taking too long. I'll speak the answer when it arrives."),
}
