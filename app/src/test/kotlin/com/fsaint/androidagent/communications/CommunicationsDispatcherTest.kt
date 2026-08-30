package com.fsaint.androidagent.communications

import com.fsaint.androidagent.model.AgentEvent
import com.fsaint.androidagent.model.PrincipalRole
import com.fsaint.androidagent.model.ScopedAgentSession
import com.fsaint.androidagent.policy.AgentContext
import com.fsaint.androidagent.policy.Principal
import com.fsaint.androidagent.policy.PrincipalDirectory
import com.fsaint.androidagent.policy.ScopeRegistry
import com.fsaint.androidagent.policy.ScopedContextBuilder
import com.fsaint.androidagent.policy.ScopedToolRouter
import com.fsaint.androidagent.runtime.AgentRuntime
import com.fsaint.androidagent.runtime.AuditStore
import com.fsaint.androidagent.runtime.EventStore
import com.fsaint.androidagent.runtime.ModelProvider
import com.fsaint.androidagent.runtime.PlannedAction
import com.fsaint.androidagent.runtime.ReplySender
import com.fsaint.androidagent.runtime.VerificationEngine
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class CommunicationsDispatcherTest {
    @Test
    fun unknownSmsDispatchesAnUnknownScopedSession() = runTest {
        val planner = RecordingPlanner()
        val scopes = ScopeRegistry()
        val runtime = AgentRuntime(
            events = NoOpEventStore,
            audit = NoOpAuditStore,
            planner = planner,
            contextBuilder = ScopedContextBuilder(scopes, emptyMap()),
            tools = ScopedToolRouter(emptyMap(), scopes),
            verification = VerificationEngine(),
            replies = NoOpReplySender,
        )
        val dispatcher = CommunicationsDispatcher(EmptyPrincipalDirectory, scopes, runtime)
        val event = AgentEvent("1", "sms.received", "+14155550199", 1, mapOf("sender" to "+14155550199"))

        dispatcher.dispatch(event, "SMS")

        assertEquals(PrincipalRole.UNKNOWN, planner.session?.role)
        assertEquals("SMS", planner.session?.channel)
    }

    @Test
    fun formattedUnknownNumbersShareTheSameNormalizedSession() = runTest {
        val planner = RecordingPlanner()
        val scopes = ScopeRegistry()
        val runtime = AgentRuntime(
            events = NoOpEventStore,
            audit = NoOpAuditStore,
            planner = planner,
            contextBuilder = ScopedContextBuilder(scopes, emptyMap()),
            tools = ScopedToolRouter(emptyMap(), scopes),
            verification = VerificationEngine(),
            replies = NoOpReplySender,
        )
        val dispatcher = CommunicationsDispatcher(EmptyPrincipalDirectory, scopes, runtime)

        dispatcher.dispatch(AgentEvent("first", "sms.received", "(415) 555-0199", 1), "SMS")
        dispatcher.dispatch(AgentEvent("second", "sms.received", "+1 415 555 0199", 2), "SMS")

        assertEquals(
            listOf("unknown:+14155550199", "unknown:+14155550199"),
            planner.sessions.map(ScopedAgentSession::principalId),
        )
        assertEquals(planner.sessions[0], planner.sessions[1])
    }

    @Test
    fun notificationSourceUsesStablePackagePrincipalWithoutPhoneNormalization() = runTest {
        val planner = RecordingPlanner()
        val scopes = ScopeRegistry()
        val dispatcher = CommunicationsDispatcher(EmptyPrincipalDirectory, scopes, runtimeFor(planner, scopes))

        dispatcher.dispatch(AgentEvent("notification", "notification.posted", "com.example.mail", 1), "NOTIFICATION")

        assertEquals("notification:com.example.mail", planner.session?.principalId)
        assertEquals(PrincipalRole.UNKNOWN, planner.session?.role)
    }

    private fun runtimeFor(planner: RecordingPlanner, scopes: ScopeRegistry) = AgentRuntime(
        events = NoOpEventStore,
        audit = NoOpAuditStore,
        planner = planner,
        contextBuilder = ScopedContextBuilder(scopes, emptyMap()),
        tools = ScopedToolRouter(emptyMap(), scopes),
        verification = VerificationEngine(),
        replies = NoOpReplySender,
    )
}

private object EmptyPrincipalDirectory : PrincipalDirectory {
    override suspend fun owner(): Principal? = null
    override suspend fun lookup(e164: String): Principal? = null
    override suspend fun list(): List<Principal> = emptyList()
    override suspend fun upsert(principal: Principal) = Unit
    override suspend fun removeKnown(e164: String): Boolean = false
}

private class RecordingPlanner : ModelProvider {
    var session: ScopedAgentSession? = null
    val sessions = mutableListOf<ScopedAgentSession>()

    override suspend fun plan(session: ScopedAgentSession, event: AgentEvent, context: AgentContext): PlannedAction {
        this.session = session
        sessions += session
        return PlannedAction.Escalate(com.fsaint.androidagent.runtime.Escalation("escalation", session.id, event.source, "", "", ""))
    }
}

private object NoOpEventStore : EventStore {
    override suspend fun enqueue(event: AgentEvent) = Unit
    override suspend fun markCompleted(eventId: String) = Unit
}

private object NoOpAuditStore : AuditStore {
    override suspend fun append(record: com.fsaint.androidagent.model.AuditRecord) = Unit
}

private object NoOpReplySender : ReplySender {
    override suspend fun send(recipient: String, text: String) = Unit
}
