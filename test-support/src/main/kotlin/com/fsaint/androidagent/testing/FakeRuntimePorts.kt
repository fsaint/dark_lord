package com.fsaint.androidagent.runtime

import com.fsaint.androidagent.model.AgentEvent
import com.fsaint.androidagent.model.AuditRecord
import com.fsaint.androidagent.model.ScopedAgentSession
import com.fsaint.androidagent.model.ToolCall
import com.fsaint.androidagent.policy.AgentContext

class InMemoryEventStore : EventStore {
    val enqueued = mutableListOf<AgentEvent>()
    val completed = mutableSetOf<String>()
    val pendingReplies = mutableMapOf<String, PendingReply>()
    val toolEffects = mutableMapOf<String, ToolEffectReservation>()
    var failNextPendingReplySave: Throwable? = null

    override suspend fun enqueue(event: AgentEvent) {
        if (enqueued.none { it.id == event.id }) enqueued += event
    }

    override suspend fun pendingReply(eventId: String): PendingReply? = pendingReplies[eventId]
    override suspend fun savePendingReply(reply: PendingReply) {
        failNextPendingReplySave?.let { failure ->
            failNextPendingReplySave = null
            throw failure
        }
        pendingReplies[reply.eventId] = reply
    }
    override suspend fun clearPendingReply(eventId: String) { pendingReplies.remove(eventId) }
    override suspend fun reserveToolEffect(eventId: String, tool: ToolCall): ToolEffectReservation {
        val key = "$eventId:${tool.name}:${tool.arguments.toSortedMap()}"
        return toolEffects[key] ?: ToolEffectReservation.Reserved.also { toolEffects[key] = ToolEffectReservation.Pending }
    }
    override suspend fun completeToolEffect(eventId: String, tool: ToolCall, replyText: String) {
        toolEffects["$eventId:${tool.name}:${tool.arguments.toSortedMap()}"] = ToolEffectReservation.Completed(replyText)
    }
    override suspend fun markCompleted(eventId: String) { completed += eventId }
}
class InMemoryAuditStore : AuditStore { val records = mutableListOf<AuditRecord>(); override suspend fun append(record: AuditRecord) { records += record } }
class InMemoryReplySender : ReplySender { val sent = mutableListOf<SentReply>(); override suspend fun send(channel: String, recipient: String, text: String) { sent += SentReply(channel, recipient, text) } }
class InMemoryEscalationStore : EscalationStore { private val values = mutableMapOf<String, Escalation>(); val resolved = mutableSetOf<String>(); override suspend fun save(escalation: Escalation) { values[escalation.id] = escalation }; override suspend fun resolve(id: String, decision: OwnerDecision): Escalation? = values[id]?.also { resolved += id } }
class FakeModelProvider(private val action: PlannedAction) : LegacyModelProvider { override suspend fun plan(session: ScopedAgentSession, event: AgentEvent, context: AgentContext): PlannedAction = action }
