package com.fsaint.androidagent.communications

import com.fsaint.androidagent.model.PrincipalRole
import com.fsaint.androidagent.model.ToolError
import com.fsaint.androidagent.model.ToolResult
import com.fsaint.androidagent.model.VerificationState
import com.fsaint.androidagent.policy.Principal
import com.fsaint.androidagent.policy.PrincipalDirectory
import com.fsaint.androidagent.runtime.OwnerDecision

class OwnerSmsCommandHandler(
    private val principals: PrincipalDirectory,
    private val statusProvider: (() -> String)? = null,
    private val escalationResolver: suspend (id: String, decision: OwnerDecision) -> Boolean = { _, _ -> false },
) {
    suspend fun handle(sender: Principal, body: String): ToolResult<String> {
        if (sender.role != PrincipalRole.OWNER) {
            return ToolResult(false, error = ToolError.SCOPE_DENIED)
        }

        val command = body.trim()
        if (command.equals(STATUS, ignoreCase = true)) {
            val knownCount = principals.list().count { it.role == PrincipalRole.KNOWN }
            val status = statusProvider?.invoke() ?: "Principal administration ready; $knownCount known principal(s)."
            return ToolResult(
                success = true,
                payload = status,
                verification = VerificationState.VERIFIED,
            )
        }

        DECISION.matchEntire(command)?.let { match ->
            val decision = if (match.groupValues[1].equals("APPROVE", ignoreCase = true)) {
                OwnerDecision.Approve
            } else {
                OwnerDecision.Reject
            }
            val escalationId = match.groupValues[2]
            return if (escalationResolver(escalationId, decision)) {
                ToolResult(true, "${decision.name}d $escalationId.", verification = VerificationState.VERIFIED)
            } else {
                ToolResult(false, error = ToolError.NOT_FOUND)
            }
        }

        ADD.matchEntire(command)?.let { match ->
            val e164 = match.groupValues[1]
            if (principals.owner()?.e164 == e164) {
                return ToolResult(false, error = ToolError.NOT_FOUND)
            }
            principals.upsert(Principal("known:$e164", e164, PrincipalRole.KNOWN))
            return ToolResult(true, "Added $e164 as a known principal.", verification = VerificationState.VERIFIED)
        }

        REMOVE.matchEntire(command)?.let { match ->
            val e164 = match.groupValues[1]
            return if (principals.removeKnown(e164)) {
                ToolResult(true, "Removed $e164 from known principals.", verification = VerificationState.VERIFIED)
            } else {
                ToolResult(false, error = ToolError.NOT_FOUND)
            }
        }

        return ToolResult(false, error = ToolError.NOT_FOUND)
    }

    private companion object {
        const val STATUS = "STATUS"
        val ADD = Regex("KNOWN ADD (\\+[1-9]\\d{1,14})", RegexOption.IGNORE_CASE)
        val REMOVE = Regex("KNOWN REMOVE (\\+[1-9]\\d{1,14})", RegexOption.IGNORE_CASE)
        val DECISION = Regex("(APPROVE|REJECT) (\\S+)", RegexOption.IGNORE_CASE)
    }
}
