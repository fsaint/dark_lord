package com.fsaint.androidagent.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Speech capture boundary; calls must be safe from any thread. */
interface RecognizerPort {
    fun startListening()
    fun stopListening()
    fun cancel()
}

/** Hands a final transcript to the agent. Returns false when no owner is configured. */
fun interface TurnDispatcher {
    suspend fun dispatch(transcript: String): Boolean
}

/** Spoken output; [onDone] must fire exactly once per [speak], including on error or interruption. */
interface Speaker {
    fun speak(text: String, onDone: () -> Unit)
    fun stop()
    fun shutdown()
}

/**
 * Push-to-talk state machine. The side-key hold arrives as [pressed]; the utterance ends on the first of
 * [released], [endOfSpeech], or [tapToSend]. The platform never reports the key release itself, so the
 * three signals are interchangeable and later ones are ignored.
 */
class PushToTalkController(
    private val recognizer: RecognizerPort,
    private val turns: TurnDispatcher,
    private val speaker: Speaker,
    private val scope: CoroutineScope,
    private val finalizeTimeoutMs: Long = 3_000,
    private val replyTimeoutMs: Long = 60_000,
) : RecognizerEvents {
    private val lock = Any()
    private val mutableState = MutableStateFlow<VoiceTurnState>(VoiceTurnState.Idle)
    private var timer: Job? = null

    val state: StateFlow<VoiceTurnState> = mutableState

    fun pressed() = synchronized(lock) {
        when (mutableState.value) {
            VoiceTurnState.Listening, VoiceTurnState.Finalizing, VoiceTurnState.Thinking -> return
            is VoiceTurnState.Responding -> speaker.stop()
            else -> Unit
        }
        cancelTimer()
        mutableState.value = VoiceTurnState.Listening
        recognizer.startListening()
    }

    fun released() = endOfUtterance()
    override fun endOfSpeech() = endOfUtterance()
    fun tapToSend() = endOfUtterance()

    override fun transcript(text: String) {
        synchronized(lock) {
            if (mutableState.value != VoiceTurnState.Listening && mutableState.value != VoiceTurnState.Finalizing) return
            cancelTimer()
            if (text.isBlank()) {
                failLocked(VoiceTurnError.NO_SPEECH)
                return
            }
            mutableState.value = VoiceTurnState.Thinking
            startTimer(replyTimeoutMs) { if (mutableState.value == VoiceTurnState.Thinking) failLocked(VoiceTurnError.TIMEOUT) }
            scope.launch { if (!turns.dispatch(text)) fail(VoiceTurnError.NO_OWNER) }
        }
    }

    fun replyReady(text: String) = synchronized(lock) {
        cancelTimer()
        mutableState.value = VoiceTurnState.Responding(text)
        speak(text)
    }

    fun speechDone() = synchronized(lock) {
        val current = mutableState.value
        if (current is VoiceTurnState.Responding || current is VoiceTurnState.Error) mutableState.value = VoiceTurnState.Idle
    }

    /**
     * Recognizer failures only matter while capturing; a cancelled recognizer reports an error after the
     * turn has already ended. A missing microphone permission is reported from any state.
     */
    override fun fail(reason: VoiceTurnError) = synchronized(lock) {
        val current = mutableState.value
        val capturing = current == VoiceTurnState.Listening || current == VoiceTurnState.Finalizing
        val fromRecognizer = reason == VoiceTurnError.NO_SPEECH || reason == VoiceTurnError.RECOGNIZER
        if (capturing || !fromRecognizer) failLocked(reason)
    }

    private fun endOfUtterance() = synchronized(lock) {
        if (mutableState.value != VoiceTurnState.Listening) return
        mutableState.value = VoiceTurnState.Finalizing
        recognizer.stopListening()
        startTimer(finalizeTimeoutMs) {
            if (mutableState.value == VoiceTurnState.Finalizing) {
                recognizer.cancel()
                failLocked(VoiceTurnError.NO_SPEECH)
            }
        }
    }

    private fun failLocked(reason: VoiceTurnError) {
        cancelTimer()
        mutableState.value = VoiceTurnState.Error(reason)
        speak(reason.spokenMessage)
    }

    private fun speak(text: String) = speaker.speak(text) { speechDone() }

    private fun startTimer(delayMs: Long, onExpiry: () -> Unit) {
        timer?.cancel()
        timer = scope.launch {
            delay(delayMs)
            synchronized(lock) { onExpiry() }
        }
    }

    private fun cancelTimer() {
        timer?.cancel()
        timer = null
    }
}
