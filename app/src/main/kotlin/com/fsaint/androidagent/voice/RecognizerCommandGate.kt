package com.fsaint.androidagent.voice

import java.util.concurrent.atomic.AtomicLong

/** Latest intent wins even when main-thread commands overtake already-posted background commands. */
internal class RecognizerCommandGate(private val dispatch: (() -> Unit) -> Unit) {
    private val generation = AtomicLong()
    fun submit(command: () -> Unit) {
        val expected = generation.incrementAndGet()
        dispatch { if (expected == generation.get()) command() }
    }
}
