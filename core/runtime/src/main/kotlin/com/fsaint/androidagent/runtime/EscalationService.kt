package com.fsaint.androidagent.runtime

class EscalationService(
    private val store: EscalationStore,
    private val replies: ReplySender,
    private val ownerRecipient: suspend () -> String? = { null },
) {
    suspend fun create(escalation: Escalation) {
        store.save(escalation)
        ownerRecipient()?.takeIf(String::isNotBlank)?.let { owner ->
            replies.send(
                "SMS",
                owner,
                "Approval required ${escalation.id}: ${escalation.question} Reply APPROVE ${escalation.id} or REJECT ${escalation.id}.",
            )
        }
    }

    suspend fun resolve(id: String, decision: OwnerDecision): Boolean {
        val escalation = store.resolve(id, decision) ?: return false
        val text = if (decision == OwnerDecision.Approve) escalation.proposedAction else "The owner declined this request."
        replies.send(escalation.channel, escalation.recipient, text)
        return true
    }
}
