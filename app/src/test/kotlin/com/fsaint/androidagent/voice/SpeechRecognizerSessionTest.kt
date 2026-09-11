package com.fsaint.androidagent.voice

import kotlin.test.*

class SpeechRecognizerSessionTest {
    private class Engine : SpeechEngine {
        val listeners = mutableListOf<RecognizerEvents>()
        var stops = 0
        var cancels = 0
        var destroys = 0
        override fun start(events: RecognizerEvents) { listeners += events }
        override fun stop() { stops++ }
        override fun cancel() { cancels++ }
        override fun destroy() { destroys++ }
    }
    private class Events : RecognizerEvents {
        val text = mutableListOf<String>()
        val errors = mutableListOf<VoiceTurnError>()
        override fun endOfSpeech() = Unit
        override fun transcript(text: String) { this.text += text }
        override fun fail(reason: VoiceTurnError) { errors += reason }
    }
    @Test fun normalCompletionReusesEngineAndDropsTerminalCallbacks() {
        val engines = mutableListOf<Engine>()
        val session = SpeechRecognizerSession { Engine().also { engines += it } }
        val first = Events()
        session.start(first)
        val old = engines.single().listeners.single()
        old.transcript("one")
        old.fail(VoiceTurnError.DISCONNECTED)
        val next = Events()
        session.start(next)
        old.transcript("stale")
        engines.single().listeners.last().transcript("two")
        assertEquals(listOf("one"), first.text)
        assertTrue(first.errors.isEmpty())
        assertEquals(listOf("two"), next.text)
        assertEquals(0, engines.single().destroys)
        session.close()
        assertEquals(1, engines.single().destroys)
    }
    @Test fun cancelRetiresBeforeCallbacksAndNewAttemptUsesNewEngine() {
        val engines = mutableListOf<Engine>()
        val session = SpeechRecognizerSession { Engine().also { engines += it } }
        val events = Events()
        session.start(events)
        val old = engines.single().listeners.single()
        session.cancel()
        old.fail(VoiceTurnError.DISCONNECTED)
        old.transcript("cancelled")
        session.start(events)
        assertEquals(2, engines.size)
        assertEquals(1, engines.first().cancels)
        assertEquals(1, engines.first().destroys)
        assertTrue(events.text.isEmpty())
        assertTrue(events.errors.isEmpty())
    }
    @Test fun errorIsTerminalAndRetiresEngine() {
        val engine = Engine()
        val session = SpeechRecognizerSession { engine }
        val events = Events()
        session.start(events)
        engine.listeners.single().fail(VoiceTurnError.DISCONNECTED)
        engine.listeners.single().transcript("late")
        assertEquals(listOf(VoiceTurnError.DISCONNECTED), events.errors)
        assertTrue(events.text.isEmpty())
        assertEquals(1, engine.destroys)
    }
    @Test fun stopIsSentAtMostOnceAndNeverAfterNaturalEnd() {
        val engine = Engine()
        val session = SpeechRecognizerSession { engine }
        session.start(Events())
        engine.listeners.last().endOfSpeech()
        session.stop()
        assertEquals(0, engine.stops)
        engine.listeners.last().transcript("done")
        session.start(Events())
        session.stop()
        session.stop()
        assertEquals(1, engine.stops)
    }
}
