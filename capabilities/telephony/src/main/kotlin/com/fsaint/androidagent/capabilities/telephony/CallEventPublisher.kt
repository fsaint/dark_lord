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
        sink.publish(
            AgentEvent(
                id = "call:${call.id}:$state:$occurredAt",
                type = "call.state",
                source = call.id,
                occurredAtEpochMs = occurredAt,
                payload = mapOf(
                    "callId" to call.id,
                    "state" to state.toString(),
                    "capabilities" to capabilities.toString(),
                ),
            ),
        )
    }
}

internal object NoOpCallEventSink : CallEventSink {
    override fun publish(event: AgentEvent) = Unit
}
