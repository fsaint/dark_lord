package com.fsaint.androidagent.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** Main-thread scheduling boundary: newest queued start wins; idle cleanup never stops a new turn. */
internal class ScheduledSpeechRecognizer(
    private val scope: CoroutineScope,
    private val available: () -> Boolean = { true },
    private val diagnostic: (String) -> Unit = {},
    factory: () -> SpeechEngine,
) {
    private val session = SpeechRecognizerSession(factory)
    private var pending: Job? = null
    private var idle: Job? = null
    private var currentEvents: RecognizerEvents? = null

    fun start(events: RecognizerEvents) {
        pending?.cancel()
        idle?.cancel()
        if (session.isActive) session.cancel()
        currentEvents = events
        pending = scope.launch {
            yield() // Coalesce starts queued during the same main-loop turn.
            pending = null
            try {
                if (available()) session.start(events) else events.fail(VoiceTurnError.UNAVAILABLE)
            } catch (_: SecurityException) { events.fail(VoiceTurnError.MICROPHONE_PERMISSION) }
            catch (_: RuntimeException) { events.fail(VoiceTurnError.RECOGNIZER) }
        }
    }

    fun stop() {
        val events = currentEvents
        if (pending != null) {
            cancel()
            events?.fail(VoiceTurnError.NO_SPEECH)
            return
        }
        try { session.stop() }
        catch (_: SecurityException) { cancel(); events?.fail(VoiceTurnError.MICROPHONE_PERMISSION) }
        catch (_: RuntimeException) { cancel(); events?.fail(VoiceTurnError.RECOGNIZER) }
    }

    fun cancel() {
        pending?.cancel()
        pending = null
        idle?.cancel()
        idle = null
        currentEvents = null
        session.cancel()
    }

    fun shutdown() {
        if (pending != null || session.isActive) { cancel(); return }
        currentEvents = null
        idle?.cancel()
        idle = scope.launch {
            delay(30_000)
            session.close()
            diagnostic("idle recognizer released")
        }
    }
}
