package com.fsaint.androidagent.communications

import com.fsaint.androidagent.model.AuditRecord
import com.fsaint.androidagent.model.AuthorizationDecision
import com.fsaint.androidagent.model.VerificationState
import com.fsaint.androidagent.policy.Principal
import com.fsaint.androidagent.policy.PrincipalDirectory
import com.fsaint.androidagent.runtime.AuditStore
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** Owns the one-time first-owner write and its required audit record. */
class OwnerProvisioningService(
    private val principals: PrincipalDirectory,
    private val audit: AuditStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val auditId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun provision(e164: String): Result<Principal> = try {
        require(principals.owner() == null) { "An owner is already provisioned" }
        val owner = principals.provisionInitialOwner(e164)
        audit.append(
            AuditRecord(
                id = auditId(),
                occurredAtEpochMs = clock(),
                principalId = owner.id,
                scopeId = "owner",
                tool = "owner.provision.initial",
                authorization = AuthorizationDecision.ALLOW,
                verification = VerificationState.VERIFIED,
                result = "Initial owner provisioned",
            ),
        )
        Result.success(owner)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        Result.failure(failure)
    }
}
