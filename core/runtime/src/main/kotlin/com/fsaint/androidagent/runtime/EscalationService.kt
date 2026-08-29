package com.fsaint.androidagent.runtime

class EscalationService(private val store: EscalationStore, private val replies: ReplySender) {
    suspend fun create(escalation: Escalation) = store.save(escalation)
    suspend fun resolve(id: String, decision: OwnerDecision) {
        val escalation = store.resolve(id, decision) ?: return
        val text = if (decision == OwnerDecision.Approve) escalation.proposedAction else "The owner declined this request."
        replies.send(escalation.recipient, text)
    }
}
