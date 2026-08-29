package com.fsaint.androidagent.capabilities.sms

import android.content.Context
import com.fsaint.androidagent.model.AgentCapability
import com.fsaint.androidagent.model.AgentEvent
import com.fsaint.androidagent.model.AgentTool
import com.fsaint.androidagent.model.CapabilityStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

data class SmsTool(override val id: String) : AgentTool

class SmsCapability(context: Context) : AgentCapability, SmsEventSink {
    private val eventFlow = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 64)
    val receiver = SmsBroadcastReceiver(this)
    val replySender = SmsReplySender(context, this)
    private var current = CapabilityStatus(available = false)

    override val id = "sms"
    override val version = "1.0"

    override suspend fun initialize(): CapabilityStatus {
        current = CapabilityStatus(available = true)
        return current
    }

    override fun tools(): List<AgentTool> = listOf(SmsTool("sms.reply"))
    override fun events(): Flow<AgentEvent> = eventFlow
    override fun status(): CapabilityStatus = current

    override fun publish(event: AgentEvent) {
        eventFlow.tryEmit(event)
    }
}
