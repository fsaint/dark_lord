package com.fsaint.androidagent.communications

import com.fsaint.androidagent.model.AgentEvent
import com.fsaint.androidagent.model.AuditRecord
import com.fsaint.androidagent.model.AuthorizationDecision
import com.fsaint.androidagent.model.PrincipalRole
import com.fsaint.androidagent.model.ToolError
import com.fsaint.androidagent.model.ToolResult
import com.fsaint.androidagent.model.VerificationState
import com.fsaint.androidagent.policy.Principal
import com.fsaint.androidagent.runtime.AuditStore
import com.fsaint.androidagent.runtime.EventStore
import com.fsaint.androidagent.runtime.ReplySender

/** Persists and audits administrative SMS commands without sending them to the model planner. */
class OwnerSmsCommandProcessor(
    private val handler: OwnerSmsCommandHandler,
    private val events: EventStore,
    private val audit: AuditStore,
    private val replies: ReplySender,
) {
    suspend fun process(sender: Principal, event: AgentEvent): ToolResult<String> {
        events.enqueue(event)
        val result = handler.handle(sender, event.payload["body"].orEmpty())
        audit.append(
            AuditRecord(
                id = "${event.id}:owner.command",
                occurredAtEpochMs = event.occurredAtEpochMs,
                eventId = event.id,
                principalId = sender.id,
                scopeId = sender.role.name.lowercase(),
                sessionId = "${sender.id}:SMS",
                tool = "owner.command",
                authorization = if (sender.role == PrincipalRole.OWNER) AuthorizationDecision.ALLOW else AuthorizationDecision.DENY,
                verification = result.verification,
                result = result.payload ?: result.error?.name.orEmpty(),
            ),
        )
        events.markCompleted(event.id)
        replies.send(event.source, replyFor(result))
        return result
    }

    private fun replyFor(result: ToolResult<String>): String = result.payload ?: when (result.error) {
        ToolError.SCOPE_DENIED -> "I’m not allowed to administer principals."
        else -> "That administration command is not supported."
    }
}
