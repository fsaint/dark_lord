package com.fsaint.androidagent.communications

import com.fsaint.androidagent.model.PrincipalRole
import com.fsaint.androidagent.model.ToolError
import com.fsaint.androidagent.policy.Principal
import com.fsaint.androidagent.policy.PrincipalDirectory
import com.fsaint.androidagent.runtime.OwnerDecision
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OwnerSmsCommandHandlerTest {
    private val directory = RecordingPrincipalDirectory()
    private val handler = OwnerSmsCommandHandler(directory)
    private val owner = Principal("owner", "+14155550100", PrincipalRole.OWNER)
    private val unknown = Principal("unknown", "+14155550101", PrincipalRole.UNKNOWN)

    @Test
    fun knownAddIsAcceptedOnlyFromOwner() = runTest {
        val ownerResult = handler.handle(owner, "  known add +14155550102  ")
        val unknownResult = handler.handle(unknown, "KNOWN ADD +14155550103")

        assertTrue(ownerResult.success)
        assertEquals(Principal("known:+14155550102", "+14155550102", PrincipalRole.KNOWN), directory.saved.single())
        assertEquals(ToolError.SCOPE_DENIED, unknownResult.error)
        assertEquals(1, directory.saved.size)
    }

    @Test
    fun malformedKnownNumberDoesNotChangeTheDirectory() = runTest {
        val result = handler.handle(owner, "KNOWN ADD 415-555-0102")

        assertFalse(result.success)
        assertEquals(ToolError.NOT_FOUND, result.error)
        assertTrue(directory.saved.isEmpty())
    }

    @Test
    fun knownRemoveIsCaseInsensitiveAndTrimmed() = runTest {
        val result = handler.handle(owner, " known remove +14155550102 ")

        assertTrue(result.success)
        assertEquals(listOf("+14155550102"), directory.removed)
    }

    @Test
    fun nonOwnerStatusIsScopeDenied() = runTest {
        val result = handler.handle(unknown, "STATUS")

        assertEquals(ToolError.SCOPE_DENIED, result.error)
    }

    @Test
    fun ownerCannotBeAddedAsKnownPrincipal() = runTest {
        directory.ownerPrincipal = owner

        val result = handler.handle(owner, "KNOWN ADD +14155550100")

        assertFalse(result.success)
        assertTrue(directory.saved.isEmpty())
    }

    @Test
    fun ownerCanApprovePersistedEscalationButUnknownSenderCannot() = runTest {
        val decisions = mutableListOf<Pair<String, OwnerDecision>>()
        val handler = OwnerSmsCommandHandler(directory, escalationResolver = { id, decision ->
            decisions += id to decision
            true
        })

        val ownerResult = handler.handle(owner, "  approve escalation:sms:42  ")
        val unknownResult = handler.handle(unknown, "REJECT escalation:sms:42")

        assertTrue(ownerResult.success)
        assertEquals(listOf("escalation:sms:42" to OwnerDecision.Approve), decisions)
        assertEquals(ToolError.SCOPE_DENIED, unknownResult.error)
    }
}

private class RecordingPrincipalDirectory : PrincipalDirectory {
    val saved = mutableListOf<Principal>()
    val removed = mutableListOf<String>()
    var ownerPrincipal: Principal? = null

    override suspend fun owner(): Principal? = ownerPrincipal
    override suspend fun provisionInitialOwner(e164: String): Principal = error("Not supported by this test directory")
    override suspend fun lookup(e164: String): Principal? = null
    override suspend fun list(): List<Principal> = saved
    override suspend fun upsert(principal: Principal) {
        saved += principal
    }

    override suspend fun removeKnown(e164: String): Boolean {
        removed += e164
        return true
    }
}
