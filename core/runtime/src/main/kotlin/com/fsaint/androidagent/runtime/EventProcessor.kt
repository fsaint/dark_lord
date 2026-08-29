package com.fsaint.androidagent.runtime

import com.fsaint.androidagent.model.AgentEvent
import com.fsaint.androidagent.model.ScopedAgentSession

class EventProcessor(private val runtime: AgentRuntime) {
    suspend fun process(session: ScopedAgentSession, event: AgentEvent) = runtime.process(session, event)
}
