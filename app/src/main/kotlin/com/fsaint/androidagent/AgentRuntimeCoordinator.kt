package com.fsaint.androidagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

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

/** Coordinates one Telegram polling job and keeps start/stop safe across lifecycle owners. */
class AgentRuntimeCoordinator(
    private val updates: TelegramUpdatesLifecyclePort,
    @Suppress("UNUSED_PARAMETER") applicationScope: CoroutineScope? = null,
) : AgentRuntimeRecovery {
    private val lifecycleLock = Any()
    private var activeJob: Job? = null
    private var stopping = false

    override val isRunning: Boolean
        get() = synchronized(lifecycleLock) { activeJob?.isActive == true }

    override fun start() {
        synchronized(lifecycleLock) {
            // A Telegram stop clears its own job before that job has fully unwound. Keep the
            // stopping barrier set until stop() joins it so a concurrent start cannot overlap.
            if (stopping || activeJob?.isActive == true) return
            activeJob = updates.start()
        }
    }

    override suspend fun stop() {
        val job = synchronized(lifecycleLock) {
            activeJob?.also {
                activeJob = null
                stopping = true
            }
        } ?: return
        try {
            updates.stop()
            job.join()
        } finally {
            synchronized(lifecycleLock) { stopping = false }
        }
    }

    /** Restores the runtime after process recreation without creating a second polling loop. */
    override suspend fun restore() = start()
}
