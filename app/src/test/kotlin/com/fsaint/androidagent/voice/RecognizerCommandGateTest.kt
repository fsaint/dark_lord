package com.fsaint.androidagent.voice

import kotlin.test.Test
import kotlin.test.assertEquals

class RecognizerCommandGateTest {
    @Test fun mainCancellationInvalidatesAlreadyPostedBackgroundStart() {
        val queued = mutableListOf<() -> Unit>()
        var onMain = false
        val gate = RecognizerCommandGate { if (onMain) it() else queued.add(it) }
        var starts = 0
        var cancels = 0
        gate.submit { starts++ }
        onMain = true
        gate.submit { cancels++ }
        queued.forEach { it() }
        assertEquals(0, starts)
        assertEquals(1, cancels)
    }

    @Test fun oldQueuedShutdownCannotCancelNewMainThreadStart() {
        val queued = mutableListOf<() -> Unit>()
        var onMain = false
        val gate = RecognizerCommandGate { if (onMain) it() else queued.add(it) }
        var active = false
        gate.submit { active = false }
        onMain = true
        gate.submit { active = true }
        queued.forEach { it() }
        assertEquals(true, active)
    }
}
