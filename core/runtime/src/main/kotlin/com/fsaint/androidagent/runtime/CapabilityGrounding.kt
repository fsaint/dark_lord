package com.fsaint.androidagent.runtime

/** A bounded guard for common unsupported access denials, not a general semantic verifier. */
internal object CapabilityGrounding {
    private val denial = Regex(
        """\b(?:i|we)\s+(?:can't|cannot|can not|don't|do not|am unable to|are unable to)\s+(?:[a-z]+\s+){0,6}(?:access|read|use|connect|retrieve|browse|interact|send|share|deliver|forward)\b""",
        RegexOption.IGNORE_CASE,
    )
    fun unverifiedDenial(text: String, transcript: ConversationTranscript): Boolean =
        denial.containsMatchIn(text.replace('’', '\'')) && transcript.turns
            .filterIsInstance<ConversationTurn.ToolOutput>().none { it.call.name != MCP_INVENTORY_TOOL }

    // During the single corrective pass, omit historical access-denial prose from model
    // context only. Keep user constraints, tool outcomes, and the stored chat unchanged.
    fun correctionHistory(messages: List<PriorMessage>): List<PriorMessage> = messages.filterNot {
        it.role == "assistant" && (denial.containsMatchIn(it.text.replace('’', '\'')) || it.text == UNVERIFIED)
    }

    const val CORRECTION = """The preceding draft denied access without checking a task tool. Re-evaluate the current authorized capability catalog and inventory, not earlier assistant denials. Use a relevant read-only discovery/account/status tool to check access before answering. Tool availability is not proof that an account is authorized. If the user requests an action, use the appropriate tool after resolving required identifiers; do not merely describe the tool. Do not perform writes just to test capability, repeat completed effects, or invent access results. If there is no matching capability, explain that specific missing capability rather than a generic model limitation."""
    const val UNVERIFIED = "I can see tools available for this session, but I haven't verified access for that request. No task action was completed."
}
