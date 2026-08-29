package com.fsaint.androidagent.runtime

import com.fsaint.androidagent.model.AgentEvent
import com.fsaint.androidagent.model.ScopedAgentSession
import com.fsaint.androidagent.policy.AgentContext

interface OpenAiResponsesTransport { suspend fun plan(session: ScopedAgentSession, event: AgentEvent, context: AgentContext): PlannedAction }
class OpenAiResponsesProvider(private val transport: OpenAiResponsesTransport) : ModelProvider {
    override suspend fun plan(session: ScopedAgentSession, event: AgentEvent, context: AgentContext): PlannedAction = transport.plan(session, event, context)
}
