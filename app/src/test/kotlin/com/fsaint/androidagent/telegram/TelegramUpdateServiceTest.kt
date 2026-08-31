package com.fsaint.androidagent.telegram

import com.fsaint.androidagent.model.AgentEvent
import com.fsaint.androidagent.runtime.TelegramMessagingClient
import com.fsaint.androidagent.runtime.TelegramReplySender
import com.fsaint.androidagent.runtime.TelegramResult
import com.fsaint.androidagent.runtime.TelegramUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TelegramUpdateServiceTest {
    @Test
    fun textUpdatesBecomeTelegramEventsAndAdvanceTheNextPollOffset() = runTest {
        val client = RecordingTelegramClient(
            listOf(
                TelegramUpdate(10, "42", "hello"),
                TelegramUpdate(11, "42", "again"),
            ),
        )
        val received = mutableListOf<Pair<AgentEvent, String>>()
        val checkpoints = RecordingCheckpointStore()
        val service = service(client, checkpoints) { event, channel -> received += event to channel }

        service.pollOnce()
        service.pollOnce()

        assertEquals(listOf(null, 12L), client.offsets)
        assertEquals(listOf(11L, 12L), checkpoints.savedOffsets)
        assertEquals(
            listOf(
                AgentEvent("telegram:10", "telegram.received", "42", 100, mapOf("sender" to "42", "body" to "hello")) to "TELEGRAM",
                AgentEvent("telegram:11", "telegram.received", "42", 100, mapOf("sender" to "42", "body" to "again")) to "TELEGRAM",
            ),
            received,
        )
    }

    @Test
    fun repeatedUpdateIdsAreDispatchedOnlyOnceWhileLaterUpdatesStillAdvanceOffset() = runTest {
        val client = RecordingTelegramClient(
            listOf(
                TelegramUpdate(20, "42", "one"),
                TelegramUpdate(20, "42", "one duplicate"),
                TelegramUpdate(21, "42", "two"),
            ),
        )
        val received = mutableListOf<AgentEvent>()
        val service = service(client, RecordingCheckpointStore()) { event, _ -> received += event }

        service.pollOnce()
        service.pollOnce()
        service.pollOnce()

        assertEquals(listOf("telegram:20", "telegram:21"), received.map(AgentEvent::id))
        assertEquals(listOf(null, 22L, 22L), client.offsets)
    }

    @Test
    fun closingTheServiceCancelsAnInFlightLongPoll() = runTest {
        val started = CompletableDeferred<Unit>()
        val client = object : TelegramMessagingClient {
            override suspend fun getUpdates(offset: Long?, timeoutSeconds: Int): List<TelegramUpdate> {
                started.complete(Unit)
                awaitCancellation()
            }

            override suspend fun sendMessage(chatId: String, text: String): TelegramResult = TelegramResult.Success()
        }
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val service = TelegramUpdateService(
            client = client,
            scope = scope,
            eventSink = { _, _ -> },
            checkpointStore = RecordingCheckpointStore(),
        )

        service.start()
        advanceUntilIdle()
        assertTrue(started.isCompleted)
        service.close()
        advanceUntilIdle()

        assertFalse(service.isRunning)
        scope.cancel()
    }

    @Test
    fun telegramRepliesAreSentOnlyToTheOriginatingTelegramChat() = runTest {
        val client = RecordingTelegramClient(emptyList())
        val sender = TelegramReplySender(client)

        sender.send("TELEGRAM", "-10042", "reply")
        sender.send("SMS", "+14155550100", "must not send")
        sender.send("TELEGRAM", "", "must not send")

        assertEquals(listOf("-10042" to "reply"), client.sent)
    }

    @Test
    fun failedDurableAcceptanceLeavesTheUpdateUnacknowledgedForRedelivery() = runTest {
        val client = RecordingTelegramClient(listOf(TelegramUpdate(10, "42", "hello")))
        val checkpoints = RecordingCheckpointStore(initialOffset = 4)
        var failAcceptance = true
        val received = mutableListOf<AgentEvent>()
        val service = service(client, checkpoints) { event, _ ->
            if (failAcceptance) error("event database is unavailable")
            received += event
        }

        assertTrue(runCatching { service.pollOnce() }.isFailure)
        failAcceptance = false
        service.pollOnce()

        assertEquals(listOf<Long?>(4L, 4L), client.offsets)
        assertEquals(listOf(11L), checkpoints.savedOffsets)
        assertEquals(listOf("telegram:10"), received.map(AgentEvent::id))
    }

    @Test
    fun cancelledAcceptanceLeavesTheUpdateUnacknowledgedForRedelivery() = runTest {
        val client = RecordingTelegramClient(listOf(TelegramUpdate(10, "42", "hello")))
        val checkpoints = RecordingCheckpointStore()
        var cancelAcceptance = true
        val service = service(client, checkpoints) { _, _ ->
            if (cancelAcceptance) throw CancellationException("application is stopping")
        }

        assertTrue(runCatching { service.pollOnce() }.isFailure)
        cancelAcceptance = false
        service.pollOnce()

        assertEquals(listOf<Long?>(null, null), client.offsets)
        assertEquals(listOf(11L), checkpoints.savedOffsets)
    }

    @Test
    fun restoredCheckpointIsUsedAsTheFirstPollOffsetAfterServiceRestart() = runTest {
        val checkpoints = RecordingCheckpointStore(initialOffset = 31)
        val client = RecordingTelegramClient(listOf(TelegramUpdate(40, "42", "hello")))
        val firstService = service(client, checkpoints) { _, _ -> }

        firstService.pollOnce()
        val restarted = service(client, checkpoints) { _, _ -> }
        restarted.pollOnce()

        assertEquals(listOf<Long?>(31L, 41L), client.offsets)
        assertEquals(listOf(41L), checkpoints.savedOffsets)
    }

    private fun TestScope.service(
        client: TelegramMessagingClient,
        checkpoints: TelegramUpdateCheckpointStore,
        sink: suspend (AgentEvent, String) -> Unit,
    ) = TelegramUpdateService(
        client = client,
        scope = TestScope(StandardTestDispatcher(testScheduler)),
        eventSink = sink,
        checkpointStore = checkpoints,
        clock = { 100L },
    )

    private class RecordingTelegramClient(
        private val updates: List<TelegramUpdate>,
    ) : TelegramMessagingClient {
        val offsets = mutableListOf<Long?>()
        val sent = mutableListOf<Pair<String, String>>()
        override suspend fun getUpdates(offset: Long?, timeoutSeconds: Int): List<TelegramUpdate> {
            offsets += offset
            return updates
        }

        override suspend fun sendMessage(chatId: String, text: String): TelegramResult {
            sent += chatId to text
            return TelegramResult.Success()
        }
    }

    private class RecordingCheckpointStore(
        private var initialOffset: Long? = null,
    ) : TelegramUpdateCheckpointStore {
        val savedOffsets = mutableListOf<Long>()

        override suspend fun loadOffset(): Long? = initialOffset

        override suspend fun saveOffset(offset: Long) {
            savedOffsets += offset
            initialOffset = offset
        }
    }
}
