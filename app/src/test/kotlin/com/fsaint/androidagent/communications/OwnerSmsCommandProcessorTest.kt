package com.fsaint.androidagent.communications

import com.fsaint.androidagent.model.AgentEvent
import com.fsaint.androidagent.model.AuditRecord
import com.fsaint.androidagent.model.AuthorizationDecision
import com.fsaint.androidagent.model.PrincipalRole
import com.fsaint.androidagent.policy.Principal
import com.fsaint.androidagent.policy.PrincipalDirectory
import com.fsaint.androidagent.runtime.AuditStore
import com.fsaint.androidagent.runtime.EventStore
import com.fsaint.androidagent.runtime.ReplySender
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class OwnerSmsCommandProcessorTest {
    @Test
    fun ownerCommandIsPersistedAndAuditedBeforeReply() = runTest {
        val events = RecordingEvents()
        val audit = RecordingAudit()
        val replies = RecordingReplies()
        val processor = OwnerSmsCommandProcessor(
            handler = OwnerSmsCommandHandler(EmptyDirectory),
            events = events,
            audit = audit,
            replies = replies,
        )
        val owner = Principal("owner", "+14155550100", PrincipalRole.OWNER)
        val event = AgentEvent("command-1", "sms.received", "+14155550100", 10, mapOf("body" to "STATUS"))

        processor.process(owner, event)

        assertEquals(listOf(event), events.enqueued)
        assertEquals(listOf("command-1"), events.completed)
        assertEquals(AuthorizationDecision.ALLOW, audit.records.single().authorization)
        assertEquals("owner.command", audit.records.single().tool)
        assertEquals("SMS", replies.replies.single().first)
        assertEquals("+14155550100", replies.replies.single().second)
    }
}

private object EmptyDirectory : PrincipalDirectory {
    override suspend fun owner(): Principal? = null
    override suspend fun lookup(e164: String): Principal? = null
    override suspend fun list(): List<Principal> = emptyList()
    override suspend fun upsert(principal: Principal) = Unit
    override suspend fun removeKnown(e164: String): Boolean = false
}

private class RecordingEvents : EventStore {
    val enqueued = mutableListOf<AgentEvent>()
    val completed = mutableListOf<String>()
    override suspend fun enqueue(event: AgentEvent) { enqueued += event }
    override suspend fun markCompleted(eventId: String) { completed += eventId }
}

private class RecordingAudit : AuditStore {
    val records = mutableListOf<AuditRecord>()
    override suspend fun append(record: AuditRecord) { records += record }
}

private class RecordingReplies : ReplySender {
    val replies = mutableListOf<Triple<String, String, String>>()
    override suspend fun send(channel: String, recipient: String, text: String) { replies += Triple(channel, recipient, text) }
}
