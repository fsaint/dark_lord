package com.fsaint.androidagent.telegram

import android.content.Context
import com.fsaint.androidagent.model.AgentEvent
import com.fsaint.androidagent.runtime.TelegramMessagingClient
import com.fsaint.androidagent.runtime.TelegramUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.LinkedHashSet

/** The durable acknowledgement boundary for an inbound Telegram update. */
fun interface TelegramInboundEventSink {
    suspend fun accept(event: AgentEvent, channel: String)
}

/**
 * Makes inbound acceptance safe after a crash between durable event insertion and offset commit.
 * A known event has already crossed the durable boundary, so it is acknowledged without invoking
 * the agent a second time.
 */
class IdempotentTelegramInboundEventSink(
    private val isAlreadyAccepted: suspend (eventId: String) -> Boolean,
    private val delegate: TelegramInboundEventSink,
) : TelegramInboundEventSink {
    override suspend fun accept(event: AgentEvent, channel: String) {
        if (!isAlreadyAccepted(event.id)) delegate.accept(event, channel)
    }
}

/** Persists the next Telegram update offset after successful durable event acceptance. */
interface TelegramUpdateCheckpointStore {
    suspend fun loadOffset(): Long?
    suspend fun saveOffset(offset: Long)
}

/** App-owned durable offset store. `commit()` makes acknowledgement survive process death. */
class SharedPreferencesTelegramUpdateCheckpointStore(context: Context) : TelegramUpdateCheckpointStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override suspend fun loadOffset(): Long? = preferences
        .takeIf { it.contains(OFFSET_KEY) }
        ?.getLong(OFFSET_KEY, 0L)

    override suspend fun saveOffset(offset: Long) {
        check(preferences.edit().putLong(OFFSET_KEY, offset).commit()) { "Could not persist Telegram update offset" }
    }

    private companion object {
        const val PREFERENCES_NAME = "telegram_update_checkpoint"
        const val OFFSET_KEY = "next_offset"
    }
}

/**
 * Owns the cancellable bot long-poll loop. A Telegram update is acknowledged only after the
 * inbound sink returns successfully and the resulting next offset is durably persisted.
 */
class TelegramUpdateService(
    private val client: TelegramMessagingClient,
    private val scope: CoroutineScope,
    private val eventSink: TelegramInboundEventSink,
    private val checkpointStore: TelegramUpdateCheckpointStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pollTimeoutSeconds: Int = DEFAULT_POLL_TIMEOUT_SECONDS,
) : AutoCloseable {
    private val lifecycleLock = Any()
    private val stateLock = Mutex()
    private val deliveredUpdateIds = object : LinkedHashSet<Long>() {
        override fun add(element: Long): Boolean {
            val added = super.add(element)
            while (size > MAX_REMEMBERED_UPDATES) remove(first())
            return added
        }
    }
    private var restored = false
    private var nextOffset: Long? = null

    @Volatile
    private var pollJob: Job? = null

    val isRunning: Boolean get() = pollJob?.isActive == true

    /** Starts at most one polling loop and immediately returns without blocking application startup. */
    fun start(): Job = synchronized(lifecycleLock) {
        pollJob?.takeIf(Job::isActive) ?: scope.launch {
            while (isActive) {
                try {
                    val received = pollOnce()
                    if (!received) delay(EMPTY_POLL_BACKOFF_MILLIS)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // The client redacts transport failures. Back off before retrying the same offset.
                    delay(FAILURE_BACKOFF_MILLIS)
                }
            }
        }.also { pollJob = it }
    }

    /** Performs one bounded poll; public for deterministic lifecycle integration tests. */
    suspend fun pollOnce(): Boolean {
        val requestedOffset = currentOffset()
        val updates = client.getUpdates(requestedOffset, pollTimeoutSeconds.coerceIn(0, MAX_POLL_TIMEOUT_SECONDS))
            .sortedBy(TelegramUpdate::updateId)
        if (updates.isEmpty()) return false
        updates.forEach { update -> accept(update) }
        return true
    }

    private suspend fun accept(update: TelegramUpdate) {
        val acknowledgedOffset = update.updateId.nextOffset()
        if (currentOffset()?.let { acknowledgedOffset <= it } == true) return
        val seen = synchronized(deliveredUpdateIds) { update.updateId in deliveredUpdateIds }
        if (!seen) {
            eventSink.accept(
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
        checkpointStore.saveOffset(acknowledgedOffset)
        stateLock.withLock { nextOffset = maxOf(nextOffset ?: Long.MIN_VALUE, acknowledgedOffset) }
        synchronized(deliveredUpdateIds) { deliveredUpdateIds.add(update.updateId) }
    }

    private suspend fun currentOffset(): Long? = stateLock.withLock {
        if (!restored) {
            nextOffset = checkpointStore.loadOffset()
            restored = true
        }
        nextOffset
    }

    /** Cancels any in-flight long poll. The caller owns when to stop this lifecycle. */
    override fun close() {
        synchronized(lifecycleLock) {
            pollJob?.cancel()
            pollJob = null
        }
    }

    private fun Long.nextOffset(): Long {
        require(this >= 0L && this < Long.MAX_VALUE) { "Invalid Telegram update id" }
        return this + 1L
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
