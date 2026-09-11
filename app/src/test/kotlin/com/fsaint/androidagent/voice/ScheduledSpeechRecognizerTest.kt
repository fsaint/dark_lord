package com.fsaint.androidagent.voice

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduledSpeechRecognizerTest {
    private class Engine : SpeechEngine {
        var events: RecognizerEvents? = null
        var starts = 0
        var destroys = 0
        override fun start(events: RecognizerEvents) { this.events = events; starts++ }
        override fun stop() = Unit
        override fun cancel() = Unit
        override fun destroy() { destroys++ }
    }
    private class Events : RecognizerEvents {
        val texts = mutableListOf<String>()
        val errors = mutableListOf<VoiceTurnError>()
        override fun transcript(text: String) { texts += text }
        override fun endOfSpeech() = Unit
        override fun fail(reason: VoiceTurnError) { errors += reason }
    }

    @Test fun queuedStartsCoalesceAndPreserveLatestCallbackIdentity() = runTest {
        val engine = Engine()
        val driver = ScheduledSpeechRecognizer(backgroundScope) { engine }
        val old = Events()
        val next = Events()
        driver.start(old)
        driver.start(next)
        runCurrent()
        engine.events!!.transcript("latest")
        assertEquals(1, engine.starts)
        assertTrue(old.texts.isEmpty())
        assertEquals(listOf("latest"), next.texts)
    }

    @Test fun cancelStopAndShutdownPreventQueuedMicrophoneStart() = runTest {
        for (stop in listOf<(ScheduledSpeechRecognizer) -> Unit>({ it.cancel() }, { it.stop() }, { it.shutdown() })) {
            val engine = Engine()
            val driver = ScheduledSpeechRecognizer(backgroundScope) { engine }
            driver.start(Events())
            stop(driver)
            runCurrent()
            assertEquals(0, engine.starts)
        }
    }

    @Test fun normalShutdownAllowsReuseThenIdleExpiryReleasesEngine() = runTest {
        val engines = mutableListOf<Engine>()
        val driver = ScheduledSpeechRecognizer(backgroundScope) { Engine().also { engines += it } }
        driver.start(Events())
        runCurrent()
        engines.single().events!!.transcript("one")
        driver.shutdown()
        advanceTimeBy(20_000)
        driver.start(Events())
        runCurrent()
        assertEquals(2, engines.single().starts)
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(0, engines.single().destroys) // Old cleanup must not kill the new capture.
        engines.single().events!!.transcript("two")
        driver.shutdown()
        advanceTimeBy(30_001)
        runCurrent()
        assertEquals(1, engines.single().destroys)
    }

    @Test fun shutdownDuringCaptureRetiresImmediately() = runTest {
        val engine = Engine()
        val driver = ScheduledSpeechRecognizer(backgroundScope) { engine }
        val events = Events()
        driver.start(events)
        runCurrent()
        driver.shutdown()
        engine.events!!.transcript("late")
        assertEquals(1, engine.destroys)
        assertTrue(events.texts.isEmpty())
    }
}
