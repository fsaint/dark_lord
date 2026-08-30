package com.fsaint.androidagent.communications

import com.fsaint.androidagent.model.AuditRecord
import com.fsaint.androidagent.model.AuthorizationDecision
import com.fsaint.androidagent.model.PrincipalRole
import com.fsaint.androidagent.model.VerificationState
import com.fsaint.androidagent.policy.Principal
import com.fsaint.androidagent.policy.PrincipalDirectory
import com.fsaint.androidagent.runtime.AuditStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OwnerProvisioningServiceTest {
    @Test
    fun successfulProvisioningCreatesOwnerAndAppendsRedactedAllowAudit() = runTest {
        val directory = FirstOwnerDirectory()
        val audit = RecordingOwnerAudit()
        val service = OwnerProvisioningService(
            principals = directory,
            audit = audit,
            clock = { 1234L },
            auditId = { "audit-1" },
        )

        val result = service.provision("+14155550100")

        assertTrue(result.isSuccess)
        assertEquals(
            Principal("owner:+14155550100", "+14155550100", PrincipalRole.OWNER),
            result.getOrThrow(),
        )
        assertEquals(
            AuditRecord(
                id = "audit-1",
                occurredAtEpochMs = 1234L,
                principalId = "owner:+14155550100",
                scopeId = "owner",
                tool = "owner.provision.initial",
                authorization = AuthorizationDecision.ALLOW,
                verification = VerificationState.VERIFIED,
                result = "Initial owner provisioned",
            ),
            audit.records.single(),
        )
        assertFalse(audit.records.single().result.orEmpty().contains("+14155550100"))
    }

    @Test
    fun existingOwnerFailsTheOneTimePreconditionWithoutReplacingIt() = runTest {
        val original = Principal("owner:+14155550100", "+14155550100", PrincipalRole.OWNER)
        val directory = FirstOwnerDirectory(original)
        val audit = RecordingOwnerAudit()
        val service = OwnerProvisioningService(directory, audit)

        val result = service.provision("+14155550101")

        assertTrue(result.isFailure)
        assertEquals(original, directory.owner())
        assertTrue(audit.records.isEmpty())
    }

    @Test
    fun provisioningDoesNotReportSuccessUntilAuditAppendCompletes() = runTest {
        val auditStarted = CompletableDeferred<Unit>()
        val allowAuditToFinish = CompletableDeferred<Unit>()
        val service = OwnerProvisioningService(
            principals = FirstOwnerDirectory(),
            audit = BlockingOwnerAudit(auditStarted, allowAuditToFinish),
        )

        val provisioning = async { service.provision("+14155550100") }
        auditStarted.await()

        assertFalse(provisioning.isCompleted)
        allowAuditToFinish.complete(Unit)
        assertTrue(provisioning.await().isSuccess)
    }
}

private class FirstOwnerDirectory(
    private var currentOwner: Principal? = null,
) : PrincipalDirectory {
    override suspend fun owner(): Principal? = currentOwner

    override suspend fun provisionInitialOwner(e164: String): Principal {
        require(currentOwner == null) { "An owner is already provisioned" }
        return Principal("owner:$e164", e164, PrincipalRole.OWNER).also { currentOwner = it }
    }

    override suspend fun lookup(e164: String): Principal? = null
    override suspend fun list(): List<Principal> = listOfNotNull(currentOwner)
    override suspend fun upsert(principal: Principal) = error("Not used by owner provisioning")
    override suspend fun removeKnown(e164: String): Boolean = false
}

private class RecordingOwnerAudit : AuditStore {
    val records = mutableListOf<AuditRecord>()
    override suspend fun append(record: AuditRecord) {
        records += record
    }
}

private class BlockingOwnerAudit(
    private val started: CompletableDeferred<Unit>,
    private val finish: CompletableDeferred<Unit>,
) : AuditStore {
    override suspend fun append(record: AuditRecord) {
        started.complete(Unit)
        finish.await()
    }
}
