package com.fsaint.androidagent

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AgentRuntimeCoordinatorTest {
    @Test
    fun startIsIdempotent() = runTest {
        val updates = FakeUpdates()
        val coordinator = AgentRuntimeCoordinator(updates)

        coordinator.start()
        coordinator.start()

        assertEquals(1, updates.starts)
    }

    @Test
    fun stopWaitsForPollingToFinish() = runTest {
        val updates = FakeUpdates()
        val coordinator = AgentRuntimeCoordinator(updates)

        coordinator.start()
        coordinator.stop()

        assertEquals(1, updates.stops)
        assertFalse(coordinator.isRunning)
    }

    @Test
    fun restoreIsIdempotent() = runTest {
        val updates = FakeUpdates()
        val coordinator = AgentRuntimeCoordinator(updates)

        coordinator.restore()
        coordinator.restore()

        assertEquals(1, updates.starts)
    }

    private class FakeUpdates : TelegramUpdatesLifecyclePort {
        var starts = 0
        var stops = 0
        private var pollingJob: CompletableJob? = null

        override fun start(): Job {
            starts += 1
            return Job().also { pollingJob = it as CompletableJob }
        }

        override suspend fun stop() {
            stops += 1
            pollingJob?.complete()
        }
    }
}
