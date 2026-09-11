package com.fsaint.androidagent.voice

import kotlinx.coroutines.Job
import kotlin.test.*

class InteractionGateTest {
    @Test fun queuedCompletionRechecksGenerationAndNeverRunsUnderGateMonitor() {
        val output = RecordingOutput()
        val callbacks = mutableListOf<() -> Unit>()
        val gate = InteractionGate(output) { callbacks += it }
        val old = gate.begin()
        var completed = false
        gate.speaker(old).speak("old") { completed = true }
        output.callback!!.invoke()
        gate.begin()
        callbacks.removeAt(0).invoke()
        assertFalse(completed)
        val fresh = gate.begin()
        gate.speaker(fresh).speak("new") { assertFalse(Thread.holdsLock(gate)); completed = true }
        output.callback!!.invoke()
        callbacks.removeAt(0).invoke()
        assertTrue(completed)
        assertFalse(gate.speaking.value)
    }
    @Test fun newInvocationStopsSpeechCancelsWorkAndRejectsLateCallbacks() {
        val output = RecordingOutput()
        val gate = InteractionGate(output)
        val first = gate.begin()
        val job = Job()
        gate.attach(first, job)
        var completed = false
        gate.speaker(first).speak("old") { completed = true }
        val second = gate.begin()
        output.callback?.invoke()
        gate.speaker(first).speak("stale") {}
        gate.speaker(first).shutdown()
        assertTrue(job.isCancelled)
        assertFalse(completed)
        assertTrue(gate.isCurrent(second))
        assertEquals(listOf("old"), output.spoken)
        gate.speaker(second).speak("new") {}
        assertEquals(listOf("old", "new"), output.spoken)
    }
    @Test fun oldLeaseStopCannotInterruptNewSpeech() {
        val output = RecordingOutput()
        val gate = InteractionGate(output)
        val old = gate.begin()
        val fresh = gate.begin()
        gate.speaker(fresh).speak("new") {}
        val stops = output.stops
        gate.stop(old)
        assertEquals(stops, output.stops)
        assertTrue(gate.isCurrent(fresh))
    }
    private class RecordingOutput : Speaker {
        val spoken = mutableListOf<String>()
        var callback: (() -> Unit)? = null
        var stops = 0
        override fun speak(text: String, onDone: () -> Unit) { spoken += text; callback = onDone }
        override fun stop() { stops++ }
        override fun shutdown() = error("Lease cannot shut down the engine")
    }
}
