package com.fsaint.androidagent.voice

/** Platform boundary. The session and engine are confined to the Android main thread. */
internal interface SpeechEngine {
    fun start(events: RecognizerEvents)
    fun stop()
    fun cancel()
    fun destroy()
}

/** Owns terminal callback isolation and healthy connection reuse independently of Android. */
internal class SpeechRecognizerSession(private val factory: () -> SpeechEngine) {
    private var engine: SpeechEngine? = null
    private var active = false
    private var stopping = false
    private var attempt = 0L
    val isActive: Boolean get() = active

    fun start(events: RecognizerEvents) {
        if (active) cancel()
        val expected = ++attempt
        active = true
        stopping = false
        val callbacks = object : RecognizerEvents {
            private fun current() = active && expected == attempt
            override fun ready() { if (current()) events.ready() }
            override fun speechStarted() { if (current()) events.speechStarted() }
            override fun partialSpeech() { if (current()) events.partialSpeech() }
            override fun endOfSpeech() {
                if (current()) { stopping = true; events.endOfSpeech() }
            }
            override fun transcript(text: String) {
                if (!current()) return
                active = false
                events.transcript(text)
            }
            override fun fail(reason: VoiceTurnError) {
                if (!current()) return
                cancel() // Invalidate before destroying: destruction may itself issue callbacks.
                events.fail(reason)
            }
        }
        try {
            val current = engine ?: factory().also { engine = it }
            current.start(callbacks)
        } catch (_: SecurityException) {
            callbacks.fail(VoiceTurnError.MICROPHONE_PERMISSION)
        } catch (_: RuntimeException) {
            callbacks.fail(VoiceTurnError.RECOGNIZER)
        }
    }

    fun stop() {
        if (!active || stopping) return
        stopping = true
        engine?.stop()
    }

    fun cancel() {
        attempt++
        val wasActive = active
        active = false
        val old = engine
        engine = null
        if (wasActive) runCatching { old?.cancel() }
        runCatching { old?.destroy() }
    }

    fun close() = cancel()
}
