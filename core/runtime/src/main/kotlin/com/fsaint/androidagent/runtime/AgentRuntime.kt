package com.fsaint.androidagent.runtime

import com.fsaint.androidagent.model.AgentEvent
import com.fsaint.androidagent.model.AuditRecord
import com.fsaint.androidagent.model.AuthorizationDecision
import com.fsaint.androidagent.model.ScopedAgentSession
import com.fsaint.androidagent.model.ToolError
import com.fsaint.androidagent.model.VerificationState
import com.fsaint.androidagent.policy.ScopedToolRouter
import com.fsaint.androidagent.policy.ScopedContextBuilder
import kotlinx.coroutines.CancellationException

class AgentRuntime(
    private val events: EventStore,
    private val audit: AuditStore,
    private val planner: ModelProvider,
    private val contextBuilder: ScopedContextBuilder,
    private val tools: ScopedToolRouter,
    private val verification: VerificationEngine,
    private val replies: ReplySender,
    private val escalations: EscalationService? = null,
    private val conversationHarness: ConversationHarness? = null,
) {
    suspend fun process(session: ScopedAgentSession, event: AgentEvent) {
        events.enqueue(event)
        val harness = conversationHarness
        if (harness != null) {
            val recipient = event.payload["sender"]?.takeIf(String::isNotBlank) ?: event.source
            val result = runCatching {
                harness.run(
                    ConversationRequest(
                        session = session,
                        event = event,
                        context = contextBuilder.build(session),
                        userText = event.payload["body"] ?: event.payload["text"].orEmpty(),
                    ),
                )
            }.getOrElse {
                if (it is CancellationException) throw it
                replies.send(session.channel, recipient, "I can't reach the conversational model yet. Add an owner API key in Dark Lord settings.")
                events.markCompleted(event.id)
                return
            }
            result.response?.let { replies.send(session.channel, recipient, it) }
            if (result.response != null || result.stopReason == ConversationStopReason.TURN_LIMIT) {
                events.markCompleted(event.id)
            }
            return
        }
        when (val action = planner.plan(session, event, contextBuilder.build(session))) {
            is PlannedAction.Tool -> processTool(session, event, action)
            is PlannedAction.Escalate -> {
                val recipient = event.payload["sender"]?.takeIf(String::isNotBlank) ?: event.source
                escalations?.create(action.escalation.copy(channel = session.channel, recipient = recipient))
                audit.append(auditRecord(event, session, "owner.ask", AuthorizationDecision.ALLOW, VerificationState.UNVERIFIED, "escalated"))
                replies.send(session.channel, recipient, "I need owner approval before I can continue.")
                events.markCompleted(event.id)
            }
        }
    }

    private suspend fun processTool(session: ScopedAgentSession, event: AgentEvent, action: PlannedAction.Tool) {
        val result = tools.execute(session, action.call)
        val verified = verification.isVerified(result)
        val authorization = if (result.error == ToolError.SCOPE_DENIED) AuthorizationDecision.DENY else AuthorizationDecision.ALLOW
        val text = when {
            verified -> result.payload.toString()
            result.recoverable -> "I couldn't complete that yet; I will retry or ask for help."
            result.error == ToolError.SCOPE_DENIED -> "I’m not allowed to access that."
            else -> "I couldn't verify that action completed."
        }
        audit.append(auditRecord(event, session, action.call.name, authorization, result.verification, result.error?.name ?: text))
        val recipient = event.payload["sender"]?.takeIf(String::isNotBlank) ?: event.source
        replies.send(session.channel, recipient, text)
        events.markCompleted(event.id)
    }

    private fun auditRecord(event: AgentEvent, session: ScopedAgentSession, tool: String, authorization: AuthorizationDecision, verification: VerificationState, result: String) =
        AuditRecord("${event.id}:$tool", event.occurredAtEpochMs, event.id, session.principalId, session.scopeId, session.id, tool = tool, authorization = authorization, verification = verification, result = result)
}
