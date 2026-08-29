package com.fsaint.androidagent.runtime

import com.fsaint.androidagent.model.AgentEvent
import com.fsaint.androidagent.model.PrincipalRole
import com.fsaint.androidagent.model.ToolCall
import com.fsaint.androidagent.model.ToolResult
import com.fsaint.androidagent.model.VerificationState
import com.fsaint.androidagent.policy.Principal
import com.fsaint.androidagent.policy.ScopeRegistry
import com.fsaint.androidagent.policy.ScopedToolRouter
import com.fsaint.androidagent.policy.ScopedContextBuilder
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentRuntimeTest {
    @Test
    fun ownerBatteryRequestCallsPermittedToolThenSendsReply() = runTest {
        val events = InMemoryEventStore()
        val replies = InMemoryReplySender()
        val scopes = ScopeRegistry()
        val owner = scopes.sessionFor(Principal("owner", "+14155550123", PrincipalRole.OWNER), "sms")
        val tool: suspend (ToolCall) -> ToolResult<Any> = { ToolResult(true, "72%", verification = VerificationState.VERIFIED) }
        val runtime = AgentRuntime(events, InMemoryAuditStore(), FakeModelProvider(PlannedAction.Tool(ToolCall("device.battery"))), ScopedContextBuilder(scopes, emptyMap()), ScopedToolRouter(mapOf("device.battery" to tool), scopes), VerificationEngine(), replies)

        runtime.process(owner, AgentEvent("evt-1", "sms.received", "sms", 1, mapOf("body" to "What's the battery?")))

        assertEquals("72%", replies.sent.single().text)
        assertTrue(events.completed.contains("evt-1"))
    }

    @Test
    fun ownerReplyResumesPersistedEscalationSession() = runTest {
        val replies = InMemoryReplySender()
        val escalations = InMemoryEscalationStore()
        val service = EscalationService(escalations, replies)
        val escalation = Escalation("esc-1", "alice", "+14155550100", "May I reply?", "approval", "draft")

        service.create(escalation)
        service.resolve("esc-1", OwnerDecision.Approve)

        assertEquals("+14155550100", replies.sent.single().recipient)
        assertTrue(escalations.resolved.contains("esc-1"))
    }

    @Test
    fun unverifiedToolResultNeverProducesSuccessLanguage() = runTest {
        val replies = InMemoryReplySender()
        val scopes = ScopeRegistry()
        val owner = scopes.sessionFor(Principal("owner", "+14155550123", PrincipalRole.OWNER), "sms")
        val tool: suspend (ToolCall) -> ToolResult<Any> = { ToolResult(true, "72%", verification = VerificationState.UNVERIFIED) }
        val runtime = AgentRuntime(InMemoryEventStore(), InMemoryAuditStore(), FakeModelProvider(PlannedAction.Tool(ToolCall("device.battery"))), ScopedContextBuilder(scopes, emptyMap()), ScopedToolRouter(mapOf("device.battery" to tool), scopes), VerificationEngine(), replies)

        runtime.process(owner, AgentEvent("evt-2", "sms.received", "sms", 2))

        assertEquals("I couldn't verify that action completed.", replies.sent.single().text)
    }
}
