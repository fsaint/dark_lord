package com.fsaint.androidagent.data

import androidx.test.core.app.ApplicationProvider
import com.fsaint.androidagent.model.AgentEvent
import com.fsaint.androidagent.model.AuditRecord
import com.fsaint.androidagent.model.AuthorizationDecision
import com.fsaint.androidagent.model.VerificationState
import com.fsaint.androidagent.runtime.PendingReply
import com.fsaint.androidagent.runtime.ToolEffectReservation
import com.fsaint.androidagent.model.ToolCall
import com.fsaint.androidagent.model.ToolResult
import com.fsaint.androidagent.model.ToolError
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class EventRepositoryTest {
    @Test
    fun eventIsRedeliveredAfterProcessRestartUntilCompleted() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val databaseName = "event-redelivery.db"
        val event = AgentEvent(
            id = "event-1",
            type = "sms.received",
            source = "test",
            occurredAtEpochMs = 1_000L,
            payload = mapOf("body" to "battery"),
        )

        val database = AgentDatabaseTestFactory.open(context, databaseName)
        try {
            EventRepository(database.eventDao()).enqueue(event)
        } finally {
            database.close()
        }

        val reopenedDatabase = AgentDatabaseTestFactory.open(context, databaseName)
        try {
            val repository = EventRepository(reopenedDatabase.eventDao())
            assertEquals(event, repository.nextPending())

            repository.markCompleted(event.id)
            assertEquals(null, repository.nextPending())
        } finally {
            reopenedDatabase.close()
        }
    }

    @Test
    fun auditRecordContainsAuthorizationAndVerification() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val record = AuditRecord(
            id = "audit-1",
            occurredAtEpochMs = 2_000L,
            tool = "device.battery",
            authorization = AuthorizationDecision.ALLOW,
            verification = VerificationState.VERIFIED,
        )

        val database = AgentDatabaseTestFactory.inMemory(context)
        try {
            val repository = AuditRepository(database.auditRecordDao())
            repository.append(record)

            assertEquals(record, repository.list().single())
        } finally {
            database.close()
        }
    }

    @Test
    fun pendingReplySurvivesRestartUntilDeliveryIsConfirmed() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val databaseName = "pending-reply-restart.db"
        val reply = PendingReply("telegram:10", "TELEGRAM", "42", "72%")

        val database = AgentDatabaseTestFactory.open(context, databaseName)
        try {
            EventRepository(database.eventDao()).savePendingReply(reply)
        } finally {
            database.close()
        }

        val reopenedDatabase = AgentDatabaseTestFactory.open(context, databaseName)
        try {
            val repository = EventRepository(reopenedDatabase.eventDao())
            assertEquals(reply, repository.pendingReply(reply.eventId))
            repository.clearPendingReply(reply.eventId)
            assertEquals(null, repository.pendingReply(reply.eventId))
        } finally {
            reopenedDatabase.close()
        }
    }

    @Test
    fun toolEffectReservationSurvivesRestartAndPreventsReplay() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val databaseName = "tool-effect-restart-${System.nanoTime()}.db"
        val call = ToolCall("device.battery")

        val database = AgentDatabaseTestFactory.open(context, databaseName)
        try {
            assertEquals(ToolEffectReservation.Reserved, EventRepository(database.eventDao()).reserveToolEffect("telegram:10", call))
        } finally {
            database.close()
        }

        val reopenedDatabase = AgentDatabaseTestFactory.open(context, databaseName)
        try {
            val repository = EventRepository(reopenedDatabase.eventDao())
            assertEquals(ToolEffectReservation.Pending, repository.reserveToolEffect("telegram:10", call))
            val result = ToolResult<Any>(false, "partial", ToolError.TIMEOUT, recoverable = true, verification = VerificationState.UNVERIFIED)
            repository.completeToolEffect("telegram:10", call, result = result)
            assertEquals(ToolEffectReservation.Completed(result), repository.reserveToolEffect("telegram:10", call))
            assertEquals(ToolEffectReservation.Reserved, repository.reserveToolEffect("telegram:10", call, turn = 1))
        } finally {
            reopenedDatabase.close()
        }
    }
}
