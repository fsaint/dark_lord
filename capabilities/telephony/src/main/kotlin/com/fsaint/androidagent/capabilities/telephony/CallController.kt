package com.fsaint.androidagent.capabilities.telephony

import android.telecom.Call
import com.fsaint.androidagent.model.ToolError
import com.fsaint.androidagent.model.ToolResult

interface CallHandle {
    val id: String
    val state: Int
    val capabilities: Int

    fun answer()
    fun reject()
    fun disconnect()
    fun hold()
    fun unhold()
    fun setMuted(muted: Boolean)
}

class CallController {
    fun answer(call: CallHandle): ToolResult<Unit> =
        when (call.state) {
            Call.STATE_RINGING -> call.complete { answer() }
            else -> unsupported()
        }

    fun reject(call: CallHandle): ToolResult<Unit> =
        when (call.state) {
            Call.STATE_RINGING -> call.complete { reject() }
            else -> unsupported()
        }

    fun disconnect(call: CallHandle): ToolResult<Unit> =
        when (call.state) {
            Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> unsupported()
            else -> call.complete { disconnect() }
        }

    fun mute(call: CallHandle, muted: Boolean): ToolResult<Unit> =
        if (call.supports(Call.Details.CAPABILITY_MUTE)) {
            call.complete { setMuted(muted) }
        } else {
            unsupported()
        }

    fun hold(call: CallHandle): ToolResult<Unit> =
        if (call.state == Call.STATE_ACTIVE && call.supports(Call.Details.CAPABILITY_HOLD)) {
            call.complete { hold() }
        } else {
            unsupported()
        }

    fun unhold(call: CallHandle): ToolResult<Unit> =
        if (call.state == Call.STATE_HOLDING && call.supports(Call.Details.CAPABILITY_HOLD)) {
            call.complete { unhold() }
        } else {
            unsupported()
        }
}

private fun CallHandle.complete(action: CallHandle.() -> Unit): ToolResult<Unit> {
    action()
    return ToolResult(success = true, payload = Unit)
}

private fun CallHandle.supports(capability: Int): Boolean = capabilities and capability == capability

private fun unsupported(): ToolResult<Unit> = ToolResult(success = false, error = ToolError.UNSUPPORTED)
