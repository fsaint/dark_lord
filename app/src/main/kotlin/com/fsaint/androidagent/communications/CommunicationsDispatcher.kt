package com.fsaint.androidagent.communications

import com.fsaint.androidagent.model.AgentEvent
import com.fsaint.androidagent.model.PrincipalRole
import com.fsaint.androidagent.policy.Principal
import com.fsaint.androidagent.policy.PrincipalDirectory
import com.fsaint.androidagent.policy.ScopeRegistry
import com.fsaint.androidagent.runtime.AgentRuntime

class CommunicationsDispatcher(
    private val principals: PrincipalDirectory,
    private val scopes: ScopeRegistry,
    private val runtime: AgentRuntime,
) {
    suspend fun dispatch(event: AgentEvent, channel: String) {
        val principal = principals.lookup(event.source)
            ?: Principal("unknown:${event.source}", event.source, PrincipalRole.UNKNOWN)
        runtime.process(scopes.sessionFor(principal, channel), event)
    }
}
