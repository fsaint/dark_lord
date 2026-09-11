package com.fsaint.androidagent.voice

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Main-thread local interaction arbiter. A service owns a lease, never the shared engine. */
class InteractionGate(private val output: Speaker, private val dispatchCallback: (() -> Unit) -> Unit = { it() }) {
    private var generation = 0L
    private var job: Job? = null
    private val current = MutableStateFlow(0L)
    private val talking = MutableStateFlow(false)
    private var utterance = 0L
    val changes = current.asStateFlow()
    val speaking = talking.asStateFlow()

    @Synchronized fun begin(): Long {
        generation++
        utterance++
        talking.value = false
        current.value = generation
        output.stop()
        job?.cancel()
        job = null
        return generation
    }
    @Synchronized fun isCurrent(token: Long) = token == generation
    @Synchronized fun attach(token: Long, work: Job) {
        if (isCurrent(token)) job = work else work.cancel()
    }
    @Synchronized fun stop(token: Long? = null) {
        if (token == null || isCurrent(token)) begin()
    }
    fun speaker(token: Long): Speaker = object : Speaker {
        override fun speak(text: String, onDone: () -> Unit) = synchronized(this@InteractionGate) {
            if (isCurrent(token)) {
                val speech = ++utterance
                talking.value = true
                output.speak(text) {
                    dispatchCallback {
                        val accepted = synchronized(this@InteractionGate) {
                            (isCurrent(token) && utterance == speech).also { if (it) talking.value = false }
                        }
                        if (accepted) onDone()
                    }
                }
            }
        }
        override fun stop() = synchronized(this@InteractionGate) { if (isCurrent(token)) { utterance++; talking.value = false; output.stop() } }
        override fun shutdown() = stop() // Never destroy an app-owned engine from an expired service.
    }
}
