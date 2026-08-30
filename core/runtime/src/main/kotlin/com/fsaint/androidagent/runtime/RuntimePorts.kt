package com.fsaint.androidagent.runtime

import com.fsaint.androidagent.model.AgentEvent
import com.fsaint.androidagent.model.AuditRecord
import com.fsaint.androidagent.model.ScopedAgentSession
import com.fsaint.androidagent.model.ToolCall
import com.fsaint.androidagent.policy.AgentContext

interface EventStore { suspend fun enqueue(event: AgentEvent); suspend fun markCompleted(eventId: String) }
interface AuditStore { suspend fun append(record: AuditRecord) }
interface ModelProvider {
    /** Legacy one-shot entry point retained for existing runtime integrations. */
    suspend fun plan(session: ScopedAgentSession, event: AgentEvent, context: AgentContext): PlannedAction {
        throw UnsupportedOperationException("one-shot planning is not implemented")
    }

    suspend fun respond(request: ModelRequest): ModelResponse {
        throw UnsupportedOperationException("conversational responses are not implemented")
    }
}
interface ReplySender { suspend fun send(channel: String, recipient: String, text: String) }
interface EscalationStore { suspend fun save(escalation: Escalation); suspend fun resolve(id: String, decision: OwnerDecision): Escalation? }

sealed interface PlannedAction { data class Tool(val call: ToolCall) : PlannedAction; data class Escalate(val escalation: Escalation) : PlannedAction }
data class Escalation(val id: String, val sessionId: String, val channel: String, val recipient: String, val question: String, val reason: String, val proposedAction: String)
enum class OwnerDecision { Approve, Reject }
data class SentReply(val channel: String, val recipient: String, val text: String)
