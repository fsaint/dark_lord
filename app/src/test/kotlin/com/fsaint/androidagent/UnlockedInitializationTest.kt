package com.fsaint.androidagent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UnlockedInitializationTest {
    @Test fun encryptedDependenciesWaitUntilUnlockAndInitializeOnlyOnce() {
        val gate = UnlockedInitialization()
        var reads = 0
        gate.initialize(false) { error("Credential storage was read before unlock") }
        gate.initialize(true) { reads++ }
        gate.initialize(true) { reads++ }
        assertEquals(1, reads)
    }

    @Test fun failedInitializationCanBeRetried() {
        val gate = UnlockedInitialization()
        assertFailsWith<IllegalStateException> { gate.initialize(true) { error("failed") } }
        var reads = 0
        gate.initialize(true) { reads++ }
        assertEquals(1, reads)
    }
}
