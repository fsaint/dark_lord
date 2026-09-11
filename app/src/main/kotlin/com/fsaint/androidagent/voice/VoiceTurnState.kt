package com.fsaint.androidagent.voice

/** Observable state of one push-to-talk turn, from the side-key hold to the spoken reply. */
sealed interface VoiceTurnState {
    data object Idle : VoiceTurnState
    data object Listening : VoiceTurnState
    data object Recovering : VoiceTurnState
    data object Finalizing : VoiceTurnState
    data object Thinking : VoiceTurnState
    data class Responding(val text: String) : VoiceTurnState
    data class Error(val reason: VoiceTurnError) : VoiceTurnState
}

/** Why a turn ended without an answer. Each reason carries the sentence spoken to the user. */
enum class VoiceTurnError(val spokenMessage: String) {
    NO_SPEECH("I didn't catch that."),
    RECOGNIZER("Speech recognition isn't available right now."),
    DISCONNECTED("The speech service disconnected. Press again and repeat your request."),
    UNAVAILABLE("No speech recognition service is available on this phone."),
    NETWORK("Speech recognition couldn't connect. Check the connection and try again."),
    BUSY("The speech service is busy. Press again to retry."),
    RECOGNITION_TIMEOUT("Speech recognition took too long. Press again and repeat your request."),
    NO_OWNER("Set up an owner in Dark Lord first."),
    MICROPHONE_PERMISSION("Dark Lord needs microphone access. Please grant it in the app."),
    TIMEOUT("That's taking too long. I'll speak the answer when it arrives."),
    MODEL_UNAVAILABLE("I couldn't finish that request. Your message and any saved photo are still in Outside chat."),
}
