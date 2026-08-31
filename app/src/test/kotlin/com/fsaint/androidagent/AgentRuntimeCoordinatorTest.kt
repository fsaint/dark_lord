package com.fsaint.androidagent

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun startDuringStopDoesNotCreateOverlappingPollingJob() = runTest {
        val updates = FakeUpdates(leavePollingJobRunning = true)
        val coordinator = AgentRuntimeCoordinator(updates)
        coordinator.start()

        val stopping = async { coordinator.stop() }
        updates.stopEntered.await()
        // Telegram stop has returned, but coordinator.stop() is still waiting for the polling job.
        coordinator.start()
        assertEquals(1, updates.starts)

        updates.completePolling()
        stopping.await()
    }

    @Test
    fun stopCancelsAndJoinsServiceOwnedQueuedWork() = runTest {
        val updates = FakeUpdates()
        val work = ServiceOwnedRuntimeWorkScope(backgroundScope)
        val coordinator = AgentRuntimeCoordinator(updates, work)
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        coordinator.start()
        coordinator.launch {
            try {
                started.complete(Unit)
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        started.await()

        coordinator.stop()

        cancelled.await()
        assertFalse(coordinator.isRunning)
    }

    @Test
    fun queuedWorkIsRejectedAfterStopAndAcceptedAfterRestart() = runTest {
        val updates = FakeUpdates()
        val work = ServiceOwnedRuntimeWorkScope(backgroundScope)
        val coordinator = AgentRuntimeCoordinator(updates, work)
        coordinator.start()
        coordinator.stop()

        assertEquals(null, coordinator.launch { error("must not run while stopped") })

        coordinator.start()
        val ran = CompletableDeferred<Unit>()
        coordinator.launch { ran.complete(Unit) }
        ran.await()
        coordinator.stop()
    }

    @Test
    fun stopStillCancelsOwnedWorkAfterPollingJobCompletesUnexpectedly() = runTest {
        val updates = FakeUpdates()
        val work = ServiceOwnedRuntimeWorkScope(backgroundScope)
        val coordinator = AgentRuntimeCoordinator(updates, work)
        coordinator.start()
        val queuedJob = coordinator.launch { awaitCancellation() }!!
        updates.completePolling()

        coordinator.stop()

        assertTrue(queuedJob.isCancelled)
        assertEquals(1, updates.stops)
    }

    private class FakeUpdates(private val leavePollingJobRunning: Boolean = false) : TelegramUpdatesLifecyclePort {
        var starts = 0
        var stops = 0
        private var pollingJob: CompletableJob? = null
        val stopEntered = CompletableDeferred<Unit>()

        override fun start(): Job {
            starts += 1
            return Job().also { pollingJob = it as CompletableJob }
        }

        override suspend fun stop() {
            stops += 1
            if (leavePollingJobRunning) {
                stopEntered.complete(Unit)
            } else {
                pollingJob?.complete()
            }
        }

        fun completePolling() { pollingJob?.complete() }
    }
}
