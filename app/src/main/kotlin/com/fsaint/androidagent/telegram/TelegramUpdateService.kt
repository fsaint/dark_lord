package com.fsaint.androidagent.telegram

import com.fsaint.androidagent.model.AgentEvent
import com.fsaint.androidagent.runtime.TelegramMessagingClient
import com.fsaint.androidagent.runtime.TelegramUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.LinkedHashSet

/**
 * Owns the bot long-poll loop and translates text updates into the application's event channel.
 * The caller supplies an application-owned scope so polling is cancelled with that lifecycle.
 */
class TelegramUpdateService(
    private val client: TelegramMessagingClient,
    private val scope: CoroutineScope,
    private val eventSink: suspend (AgentEvent, String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pollTimeoutSeconds: Int = DEFAULT_POLL_TIMEOUT_SECONDS,
) : AutoCloseable {
    private val lock = Any()
    private val deliveredUpdateIds = object : LinkedHashSet<Long>() {
        override fun add(element: Long): Boolean {
            val added = super.add(element)
            while (size > MAX_REMEMBERED_UPDATES) remove(first())
            return added
        }
    }

    @Volatile
    private var nextOffset: Long? = null
    @Volatile
    private var pollJob: Job? = null

    val isRunning: Boolean get() = pollJob?.isActive == true

    /** Starts at most one polling loop and immediately returns without blocking application startup. */
    fun start(): Job = synchronized(lock) {
        pollJob?.takeIf(Job::isActive) ?: scope.launch {
            while (isActive) {
                try {
                    val received = pollOnce()
                    if (!received) delay(EMPTY_POLL_BACKOFF_MILLIS)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // The client already redacts transport failures. Back off before the next poll.
                    delay(FAILURE_BACKOFF_MILLIS)
                }
            }
        }.also { pollJob = it }
    }

    /** Performs one bounded poll; public for deterministic lifecycle integration tests. */
    suspend fun pollOnce(): Boolean {
        val updates = client.getUpdates(nextOffset, pollTimeoutSeconds.coerceIn(0, MAX_POLL_TIMEOUT_SECONDS))
            .sortedBy(TelegramUpdate::updateId)
        if (updates.isEmpty()) return false
        updates.forEach { update ->
            nextOffset = maxOf(nextOffset ?: Long.MIN_VALUE, update.updateId + 1L)
            val shouldDeliver = synchronized(deliveredUpdateIds) { deliveredUpdateIds.add(update.updateId) }
            if (shouldDeliver) {
                eventSink(
                    AgentEvent(
                        id = "telegram:${update.updateId}",
                        type = "telegram.received",
                        source = update.chatId,
                        occurredAtEpochMs = clock(),
                        payload = mapOf("sender" to update.chatId, "body" to update.text),
                    ),
                    TELEGRAM_CHANNEL,
                )
            }
        }
        return true
    }

    /** Cancels any in-flight long poll; no application work continues after close. */
    override fun close() {
        synchronized(lock) {
            pollJob?.cancel()
            pollJob = null
        }
    }

    private companion object {
        const val TELEGRAM_CHANNEL = "TELEGRAM"
        const val DEFAULT_POLL_TIMEOUT_SECONDS = 30
        const val MAX_POLL_TIMEOUT_SECONDS = 50
        const val EMPTY_POLL_BACKOFF_MILLIS = 1_000L
        const val FAILURE_BACKOFF_MILLIS = 5_000L
        const val MAX_REMEMBERED_UPDATES = 1_024
    }
}
