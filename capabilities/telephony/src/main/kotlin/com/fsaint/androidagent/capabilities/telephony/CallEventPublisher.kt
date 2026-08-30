package com.fsaint.androidagent.capabilities.telephony

import com.fsaint.androidagent.model.AgentEvent

fun interface CallEventSink {
    fun publish(event: AgentEvent)
}

class CallEventPublisher(
    private val sink: CallEventSink = NoOpCallEventSink,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun publishState(call: CallHandle) {
        val occurredAt = now()
        val state = call.state
        val capabilities = call.capabilities
        val telephoneHandle = call.telephoneHandle
        sink.publish(
            AgentEvent(
                id = "call:${call.id}:$state:$occurredAt",
                type = "call.state",
                source = telephoneHandle ?: call.id,
                occurredAtEpochMs = occurredAt,
                payload = buildMap {
                    put("callId", call.id)
                    put("state", state.toString())
                    put("capabilities", capabilities.toString())
                    telephoneHandle?.let { put("telephoneHandle", it) }
                },
            ),
        )
    }
}

internal object NoOpCallEventSink : CallEventSink {
    override fun publish(event: AgentEvent) = Unit
}
