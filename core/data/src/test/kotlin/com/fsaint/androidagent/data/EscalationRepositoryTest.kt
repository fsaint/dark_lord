package com.fsaint.androidagent.data

import androidx.test.core.app.ApplicationProvider
import com.fsaint.androidagent.runtime.Escalation
import com.fsaint.androidagent.runtime.EscalationService
import com.fsaint.androidagent.runtime.OwnerDecision
import com.fsaint.androidagent.runtime.ReplySender
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class EscalationRepositoryTest {
    @Test
    fun ownerDecisionResumesEscalationAfterRepositoryReopen() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val databaseName = "escalation-reopen.db"
        val initialDatabase = AgentDatabaseTestFactory.open(context, databaseName)
        try {
            EscalationService(EscalationRepository(initialDatabase.durableStateDao()), RecordingReplySender()).create(
                Escalation("esc-42", "known:alice:SMS", "SMS", "+14155550100", "May I reply?", "approval", "Approved reply"),
            )
        } finally {
            initialDatabase.close()
        }

        val replies = RecordingReplySender()
        val reopenedDatabase = AgentDatabaseTestFactory.open(context, databaseName)
        try {
            val resumed = EscalationService(EscalationRepository(reopenedDatabase.durableStateDao()), replies)
                .resolve("esc-42", OwnerDecision.Approve)

            assertTrue(resumed)
            assertEquals(RecordedReply("SMS", "+14155550100", "Approved reply"), replies.sent.single())
        } finally {
            reopenedDatabase.close()
        }
    }
}

private data class RecordedReply(val channel: String, val recipient: String, val text: String)

private class RecordingReplySender : ReplySender {
    val sent = mutableListOf<RecordedReply>()
    override suspend fun send(channel: String, recipient: String, text: String) {
        sent += RecordedReply(channel, recipient, text)
    }
}
