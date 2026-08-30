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
    private val phoneNumbers: PhoneNumberNormalizer = PhoneNumberNormalizer { it },
) {
    suspend fun dispatch(event: AgentEvent, channel: String) {
        val principal = when (channel) {
            "SMS" -> resolveTelephonePrincipal(event.source)
            "CALL" -> event.payload["telephoneHandle"]
                ?.takeIf(String::isNotBlank)
                ?.let { resolveTelephonePrincipal(it) }
                ?: Principal("unknown:${event.payload["callId"] ?: event.source}", null, PrincipalRole.UNKNOWN)
            "NOTIFICATION" -> Principal("notification:${event.source}", null, PrincipalRole.UNKNOWN)
            else -> Principal("unknown:${event.source}", null, PrincipalRole.UNKNOWN)
        }
        runtime.process(scopes.sessionFor(principal, channel), event)
    }

    private suspend fun resolveTelephonePrincipal(source: String): Principal {
        val normalized = phoneNumbers.normalize(source)
        return principals.lookup(normalized)
            ?: Principal("unknown:$normalized", normalized, PrincipalRole.UNKNOWN)
    }
}

fun interface PhoneNumberNormalizer {
    fun normalize(source: String): String
}
