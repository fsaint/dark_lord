package com.fsaint.androidagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Narrow lifecycle port for the long-lived Telegram polling loop. */
interface TelegramUpdatesLifecyclePort {
    fun start(): Job
    suspend fun stop()
}

/** Entry point shared by application startup, boot recovery, and the foreground service. */
interface AgentRuntimeRecovery {
    fun start()
    suspend fun stop()
    val isRunning: Boolean
    suspend fun restore()
}

/** Lifecycle boundary for agent work accepted by Android-managed background entry points. */
interface AgentRuntimeWorkLifecyclePort {
    fun start()
    fun launch(block: suspend () -> Unit): Job?
    suspend fun stop()
}

/** A restartable child scope whose jobs exist only while the foreground runtime is active. */
class ServiceOwnedRuntimeWorkScope(
    private val parentScope: CoroutineScope,
) : AgentRuntimeWorkLifecyclePort {
    private val lifecycleLock = Any()
    private var runtimeJob: Job? = null
    private var runtimeScope: CoroutineScope? = null

    override fun start() {
        synchronized(lifecycleLock) {
            if (runtimeJob?.isActive == true) return
            val job = SupervisorJob(parentScope.coroutineContext[Job])
            runtimeJob = job
            runtimeScope = CoroutineScope(parentScope.coroutineContext.minusKey(Job) + job)
        }
    }

    override fun launch(block: suspend () -> Unit): Job? = synchronized(lifecycleLock) {
        runtimeScope?.takeIf { runtimeJob?.isActive == true }?.launch { block() }
    }

    override suspend fun stop() {
        val job = synchronized(lifecycleLock) {
            runtimeScope = null
            runtimeJob.also { runtimeJob = null }
        }
        job?.cancelAndJoin()
    }
}

private object NoQueuedRuntimeWork : AgentRuntimeWorkLifecyclePort {
    override fun start() = Unit
    override fun launch(block: suspend () -> Unit): Job? = null
    override suspend fun stop() = Unit
}

/** Coordinates one Telegram polling job and keeps start/stop safe across lifecycle owners. */
class AgentRuntimeCoordinator(
    private val updates: TelegramUpdatesLifecyclePort,
    private val queuedWork: AgentRuntimeWorkLifecyclePort = NoQueuedRuntimeWork,
) : AgentRuntimeRecovery {
    private val lifecycleLock = Any()
    private var activeJob: Job? = null
    private var stopBarrier: CompletableDeferred<Unit>? = null

    override val isRunning: Boolean
        get() = synchronized(lifecycleLock) { activeJob?.isActive == true }

    override fun start() {
        synchronized(lifecycleLock) {
            // A Telegram stop clears its own job before that job has fully unwound. Keep the
            // stopping barrier set until stop() joins it so a concurrent start cannot overlap.
            if (stopBarrier != null || activeJob?.isActive == true) return
            queuedWork.start()
            activeJob = updates.start()
        }
    }

    override suspend fun stop() {
        val existingOrNew = synchronized(lifecycleLock) {
            stopBarrier?.let { ExistingStop(it) } ?: activeJob?.let { job ->
                activeJob = null
                val barrier = CompletableDeferred<Unit>()
                stopBarrier = barrier
                NewStop(job, barrier)
            }
        } ?: return
        when (existingOrNew) {
            is ExistingStop -> existingOrNew.barrier.await()
            is NewStop -> {
                try {
                    withContext(NonCancellable) {
                        try {
                            updates.stop()
                            existingOrNew.job.join()
                        } finally {
                            queuedWork.stop()
                        }
                    }
                } finally {
                    synchronized(lifecycleLock) {
                        if (stopBarrier === existingOrNew.barrier) stopBarrier = null
                        existingOrNew.barrier.complete(Unit)
                    }
                }
            }
        }
    }

    /** Accepts queued agent work only while the visible runtime owns its lifecycle. */
    fun launch(block: suspend () -> Unit): Job? = queuedWork.launch(block)

    /** Restores the runtime after process recreation without creating a second polling loop. */
    override suspend fun restore() = start()

    private sealed interface StopState
    private data class ExistingStop(val barrier: CompletableDeferred<Unit>) : StopState
    private data class NewStop(val job: Job, val barrier: CompletableDeferred<Unit>) : StopState
}
