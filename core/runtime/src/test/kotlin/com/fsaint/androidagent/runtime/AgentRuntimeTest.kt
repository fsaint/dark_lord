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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

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
        val escalation = Escalation("esc-1", "alice", "SMS", "+14155550100", "May I reply?", "approval", "draft")

        service.create(escalation)
        service.resolve("esc-1", OwnerDecision.Approve)

        assertEquals("+14155550100", replies.sent.single().recipient)
        assertEquals("SMS", replies.sent.single().channel)
        assertTrue(escalations.resolved.contains("esc-1"))
    }

    @Test
    fun notificationEscalationKeepsNotificationReplyChannel() = runTest {
        val events = InMemoryEventStore()
        val replies = InMemoryReplySender()
        val escalations = InMemoryEscalationStore()
        val scopes = ScopeRegistry()
        val session = scopes.sessionFor(Principal("notification:mail", null, PrincipalRole.UNKNOWN), "NOTIFICATION")
        val proposed = Escalation("esc-notification", session.id, "SMS", "+not-a-phone", "Review?", "approval", "draft")
        val runtime = AgentRuntime(
            events,
            InMemoryAuditStore(),
            FakeModelProvider(PlannedAction.Escalate(proposed)),
            ScopedContextBuilder(scopes, emptyMap()),
            ScopedToolRouter(emptyMap(), scopes),
            VerificationEngine(),
            replies,
            EscalationService(escalations, replies),
        )

        runtime.process(session, AgentEvent("notification-1", "notification.posted", "com.example.mail", 1))

        assertEquals("NOTIFICATION", replies.sent.single().channel)
        assertEquals("com.example.mail", replies.sent.single().recipient)
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

    @Test
    fun failedReplyRetriesStoredResponseWithoutRunningTheToolAgain() = runTest {
        val events = InMemoryEventStore()
        val scopes = ScopeRegistry()
        val owner = scopes.sessionFor(Principal("owner", "+14155550123", PrincipalRole.OWNER), "TELEGRAM")
        var toolCalls = 0
        val replies = FailOnceReplySender()
        val tool: suspend (ToolCall) -> ToolResult<Any> = {
            toolCalls += 1
            ToolResult(true, "72%", verification = VerificationState.VERIFIED)
        }
        val runtime = AgentRuntime(
            events,
            InMemoryAuditStore(),
            FakeModelProvider(PlannedAction.Tool(ToolCall("device.battery"))),
            ScopedContextBuilder(scopes, emptyMap()),
            ScopedToolRouter(mapOf("device.battery" to tool), scopes),
            VerificationEngine(),
            replies,
        )
        val event = AgentEvent("telegram:10", "telegram.received", "10", 1, mapOf("sender" to "10"))

        assertFailsWith<IllegalStateException> { runtime.process(owner, event) }
        runtime.process(owner, event)

        assertEquals(1, toolCalls)
        assertEquals(listOf("72%"), replies.delivered)
        assertTrue(events.completed.contains(event.id))
    }

    @Test
    fun cancelledAfterToolEffectIsCheckpointedDoesNotRunTheToolAgainOnReplay() = runTest {
        val events = InMemoryEventStore()
        val scopes = ScopeRegistry()
        val owner = scopes.sessionFor(Principal("owner", "+14155550123", PrincipalRole.OWNER), "TELEGRAM")
        var toolCalls = 0
        val tool: suspend (ToolCall) -> ToolResult<Any> = {
            toolCalls += 1
            ToolResult(true, "72%", verification = VerificationState.VERIFIED)
        }
        val runtime = AgentRuntime(
            events,
            InMemoryAuditStore(),
            FakeModelProvider(PlannedAction.Tool(ToolCall("device.battery"))),
            ScopedContextBuilder(scopes, emptyMap()),
            ScopedToolRouter(mapOf("device.battery" to tool), scopes),
            VerificationEngine(),
            InMemoryReplySender(),
        )
        val event = AgentEvent("telegram:11", "telegram.received", "10", 1, mapOf("sender" to "10"))
        events.failNextPendingReplySave = CancellationException("process interrupted after tool")

        assertFailsWith<CancellationException> { runtime.process(owner, event) }
        runtime.process(owner, event)

        assertEquals(1, toolCalls)
        assertTrue(events.completed.contains(event.id))
    }

    private class FailOnceReplySender : ReplySender {
        private var failed = false
        val delivered = mutableListOf<String>()

        override suspend fun send(channel: String, recipient: String, text: String) {
            if (!failed) {
                failed = true
                throw IllegalStateException("temporary Telegram failure")
            }
            delivered += text
        }
    }
}
