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
    fun shutdown() = cancel()
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
 * later signals are ignored. Only explicit release/tap requests a platform stop.
 */
class PushToTalkController(
    private val recognizer: RecognizerPort,
    private val turns: TurnDispatcher,
    private val speaker: Speaker,
    private val scope: CoroutineScope,
    private val finalizeTimeoutMs: Long = 5_000,
    private val replyTimeoutMs: Long = 60_000,
    private val readinessTimeoutMs: Long = 5_000,
    private val recoveryDelayMs: Long = 300,
    private val diagnostic: (String) -> Unit = {},
) : RecognizerEvents {
    private val lock = Any()
    private val mutableState = MutableStateFlow<VoiceTurnState>(VoiceTurnState.Idle)
    private var timer: Job? = null
    private var work: Job? = null
    private var generation = 0L
    private var attempt = 0L
    private var retryUsed = false
    private var readyOrSpeech = false

    val state: StateFlow<VoiceTurnState> = mutableState

    fun pressed() = synchronized(lock) {
        generation++
        attempt++
        if (capturing()) recognizer.cancel()
        work?.cancel()
        if (mutableState.value is VoiceTurnState.Responding || mutableState.value is VoiceTurnState.Error) speaker.stop()
        cancelTimer()
        retryUsed = false
        startAttempt()
    }

    fun cancel() = synchronized(lock) {
        generation++
        attempt++
        cancelTimer()
        work?.cancel()
        if (capturing()) recognizer.cancel() else recognizer.shutdown()
        speaker.stop()
        mutableState.value = VoiceTurnState.Idle
    }

    fun shutdown() = synchronized(lock) {
        cancel()
    }

    /** Snapshot callback identity before starting Android recognition. */
    fun eventsForCurrentTurn(): RecognizerEvents = synchronized(lock) {
        val expected = generation
        val expectedAttempt = attempt
        object : RecognizerEvents {
            private fun current() = expected == generation && expectedAttempt == attempt
            override fun ready() = synchronized(lock) { if (current()) this@PushToTalkController.ready() }
            override fun speechStarted() = synchronized(lock) { if (current()) this@PushToTalkController.speechStarted() }
            override fun partialSpeech() = synchronized(lock) { if (current()) this@PushToTalkController.partialSpeech() }
            override fun endOfSpeech() = synchronized(lock) { if (current()) this@PushToTalkController.endOfSpeech() }
            override fun transcript(text: String) = synchronized(lock) { if (current()) this@PushToTalkController.transcript(text) }
            override fun fail(reason: VoiceTurnError) = synchronized(lock) { if (current()) this@PushToTalkController.fail(reason) }
        }
    }

    override fun ready() = synchronized(lock) {
        readyOrSpeech = true
        if (mutableState.value == VoiceTurnState.Listening) cancelTimer()
    }
    override fun speechStarted() = ready()
    override fun partialSpeech() = ready()
    fun released() = endOfUtterance(explicit = true)
    override fun endOfSpeech() = endOfUtterance(explicit = false)
    fun tapToSend() = endOfUtterance(explicit = true)

    override fun transcript(text: String) {
        synchronized(lock) {
            if (mutableState.value != VoiceTurnState.Listening && mutableState.value != VoiceTurnState.Finalizing) return
            cancelTimer()
            attempt++
            if (text.isBlank()) {
                failLocked(VoiceTurnError.NO_SPEECH)
                return
            }
            mutableState.value = VoiceTurnState.Thinking
            startTimer(replyTimeoutMs) { if (mutableState.value == VoiceTurnState.Thinking) failLocked(VoiceTurnError.TIMEOUT) }
            val expected = generation
            work = scope.launch {
                if (!turns.dispatch(text)) synchronized(lock) { if (expected == generation) fail(VoiceTurnError.NO_OWNER) }
            }
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
        val fromRecognizer = reason in setOf(VoiceTurnError.NO_SPEECH, VoiceTurnError.RECOGNIZER,
            VoiceTurnError.DISCONNECTED, VoiceTurnError.UNAVAILABLE, VoiceTurnError.NETWORK,
            VoiceTurnError.BUSY, VoiceTurnError.RECOGNITION_TIMEOUT)
        if (fromRecognizer && !capturing()) return@synchronized
        if (reason == VoiceTurnError.DISCONNECTED && mutableState.value == VoiceTurnState.Listening && !readyOrSpeech && !retryUsed) {
            retryUsed = true
            attempt++
            mutableState.value = VoiceTurnState.Recovering
            recognizer.cancel()
            diagnostic("turn=$generation attempt=$attempt retry scheduled")
            startTimer(recoveryDelayMs) {
                if (mutableState.value == VoiceTurnState.Recovering) startAttempt()
            }
        } else failLocked(reason)
    }

    private fun endOfUtterance(explicit: Boolean) = synchronized(lock) {
        if (explicit && mutableState.value == VoiceTurnState.Recovering) {
            failLocked(VoiceTurnError.DISCONNECTED)
            return@synchronized
        }
        if (mutableState.value != VoiceTurnState.Listening) return
        mutableState.value = VoiceTurnState.Finalizing
        startTimer(finalizeTimeoutMs) {
            if (mutableState.value == VoiceTurnState.Finalizing) {
                failLocked(VoiceTurnError.RECOGNITION_TIMEOUT)
            }
        }
        if (explicit) recognizer.stopListening()
    }

    private fun failLocked(reason: VoiceTurnError) {
        cancelTimer()
        attempt++
        val wasCapturing = capturing()
        mutableState.value = VoiceTurnState.Error(reason)
        if (wasCapturing) recognizer.cancel()
        diagnostic("turn=$generation attempt=$attempt failed reason=$reason")
        speak(reason.spokenMessage)
    }

    private fun capturing() = mutableState.value == VoiceTurnState.Listening ||
        mutableState.value == VoiceTurnState.Finalizing || mutableState.value == VoiceTurnState.Recovering

    private fun startAttempt() {
        attempt++
        readyOrSpeech = false
        mutableState.value = VoiceTurnState.Listening
        diagnostic("turn=$generation attempt=$attempt start retry=$retryUsed")
        startTimer(readinessTimeoutMs) {
            if (mutableState.value == VoiceTurnState.Listening && !readyOrSpeech) failLocked(VoiceTurnError.RECOGNITION_TIMEOUT)
        }
        recognizer.startListening()
    }

    private fun speak(text: String) {
        val expected = generation
        speaker.speak(text) { synchronized(lock) { if (expected == generation) speechDone() } }
    }

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
