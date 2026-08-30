package com.fsaint.androidagent.capabilities.telephony

import android.telecom.Call
import com.fsaint.androidagent.model.ToolError
import com.fsaint.androidagent.model.ToolResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

data class ActiveCall(
    val id: String,
    val telephoneHandle: String?,
    val state: Int,
    val capabilities: Int,
    val muted: Boolean,
) {
    val canAnswer: Boolean = state == Call.STATE_RINGING
    val canReject: Boolean = state == Call.STATE_RINGING
    val canDisconnect: Boolean = state != Call.STATE_DISCONNECTED && state != Call.STATE_DISCONNECTING
    val canMute: Boolean = capabilities supports Call.Details.CAPABILITY_MUTE
    val canHold: Boolean = state == Call.STATE_ACTIVE && capabilities supports Call.Details.CAPABILITY_HOLD
    val canUnhold: Boolean = state == Call.STATE_HOLDING && capabilities supports Call.Details.CAPABILITY_HOLD
}

interface CallRepository {
    val calls: StateFlow<List<ActiveCall>>

    fun call(id: String): ActiveCall?
    fun answer(id: String): ToolResult<Unit>
    fun reject(id: String): ToolResult<Unit>
    fun disconnect(id: String): ToolResult<Unit>
    fun setMuted(id: String, muted: Boolean): ToolResult<Unit>
    fun hold(id: String): ToolResult<Unit>
    fun unhold(id: String): ToolResult<Unit>
}

/**
 * Process-scoped boundary between Telecom's live [CallHandle] instances and app UI components.
 * Activities retain only opaque IDs and immutable snapshots; all framework calls stay here.
 */
class ProcessCallRepository(
    private val controller: CallController = CallController(),
) : CallRepository {
    private val handles = ConcurrentHashMap<String, CallHandle>()
    private val muted = ConcurrentHashMap<String, Boolean>()
    private val mutableCalls = MutableStateFlow<List<ActiveCall>>(emptyList())

    override val calls: StateFlow<List<ActiveCall>> = mutableCalls.asStateFlow()

    fun add(call: CallHandle) {
        handles[call.id] = call
        publish()
    }

    fun update(call: CallHandle) {
        if (handles.containsKey(call.id)) {
            handles[call.id] = call
            publish()
        }
    }

    fun remove(id: String) {
        handles.remove(id)
        muted.remove(id)
        publish()
    }

    override fun call(id: String): ActiveCall? = mutableCalls.value.firstOrNull { it.id == id }

    override fun answer(id: String): ToolResult<Unit> = withCall(id, controller::answer)

    override fun reject(id: String): ToolResult<Unit> = withCall(id, controller::reject)

    override fun disconnect(id: String): ToolResult<Unit> = withCall(id, controller::disconnect)

    override fun setMuted(id: String, muted: Boolean): ToolResult<Unit> = withCall(id) { call ->
        controller.mute(call, muted).also { result ->
            if (result.success) {
                this.muted[id] = muted
                publish()
            }
        }
    }

    override fun hold(id: String): ToolResult<Unit> = withCall(id, controller::hold)

    override fun unhold(id: String): ToolResult<Unit> = withCall(id, controller::unhold)

    private fun withCall(id: String, action: (CallHandle) -> ToolResult<Unit>): ToolResult<Unit> =
        handles[id]?.let(action) ?: ToolResult(success = false, error = ToolError.NOT_FOUND)

    private fun publish() {
        mutableCalls.value = handles.values
            .map { call ->
                ActiveCall(
                    id = call.id,
                    telephoneHandle = call.telephoneHandle,
                    state = call.state,
                    capabilities = call.capabilities,
                    muted = muted[call.id] == true,
                )
            }
            .sortedBy(ActiveCall::id)
    }
}

private infix fun Int.supports(capability: Int): Boolean = this and capability == capability
