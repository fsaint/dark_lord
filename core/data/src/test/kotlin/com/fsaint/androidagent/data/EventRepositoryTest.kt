package com.fsaint.androidagent.data

import androidx.test.core.app.ApplicationProvider
import com.fsaint.androidagent.model.AgentEvent
import com.fsaint.androidagent.model.AuditRecord
import com.fsaint.androidagent.model.AuthorizationDecision
import com.fsaint.androidagent.model.VerificationState
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
}
