package com.fsaint.androidagent.capabilities.telephony

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.telecom.Call
import android.telecom.InCallService
import android.telecom.VideoProfile
import java.util.concurrent.ConcurrentHashMap

fun interface CallUiLauncher {
    fun launch(call: CallHandle)
}

/**
 * Process-wide wiring used by the application before Telecom creates this service.
 */
object AgentInCallServiceDependencies {
    @Volatile
    private var eventSink: CallEventSink = NoOpCallEventSink

    @Volatile
    private var uiLauncher: CallUiLauncher = NoOpCallUiLauncher

    fun configure(eventSink: CallEventSink, uiLauncher: CallUiLauncher) {
        this.eventSink = eventSink
        this.uiLauncher = uiLauncher
    }

    internal fun eventPublisher(): CallEventPublisher = CallEventPublisher(eventSink)
    internal fun launcher(): CallUiLauncher = uiLauncher
}

class AgentInCallService(
    private val eventPublisher: CallEventPublisher = AgentInCallServiceDependencies.eventPublisher(),
    private val uiLauncher: CallUiLauncher = AgentInCallServiceDependencies.launcher(),
) : InCallService() {
    private val calls = ConcurrentHashMap<String, CallHandle>()
    private val callbacks = ConcurrentHashMap<String, Call.Callback>()

    override fun onBind(intent: Intent): IBinder = super.onBind(intent) ?: Binder()

    override fun onCallAdded(call: Call) {
        val handle = TelecomCallHandle(call, ::setMuted)
        calls[handle.id] = handle
        val callback = stateCallback(handle)
        callbacks[handle.id] = callback
        call.registerCallback(callback)
        publishAndLaunch(handle)
    }

    /** Testable boundary for a call supplied by Telecom. */
    fun onCallAdded(call: CallHandle) {
        calls[call.id] = call
        publishAndLaunch(call)
    }

    override fun onCallRemoved(call: Call) {
        val handle = TelecomCallHandle(call, ::setMuted)
        callbacks.remove(handle.id)?.let(call::unregisterCallback)
        calls.remove(handle.id)
    }

    fun activeCalls(): List<CallHandle> = calls.values.toList()

    private fun stateCallback(handle: CallHandle): Call.Callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            eventPublisher.publishState(handle)
        }

        override fun onDetailsChanged(call: Call, details: Call.Details) {
            eventPublisher.publishState(handle)
        }
    }

    private fun publishAndLaunch(call: CallHandle) {
        eventPublisher.publishState(call)
        uiLauncher.launch(call)
    }
}

private class TelecomCallHandle(
    private val call: Call,
    private val mute: (Boolean) -> Unit,
) : CallHandle {
    override val id: String
        get() = "telecom:${System.identityHashCode(call)}"
    override val state: Int
        get() = call.details.state
    override val capabilities: Int
        get() = call.details.callCapabilities

    override fun answer() {
        call.answer(VideoProfile.STATE_AUDIO_ONLY)
    }

    override fun reject() {
        call.reject(Call.REJECT_REASON_DECLINED)
    }

    override fun disconnect() {
        call.disconnect()
    }

    override fun hold() {
        call.hold()
    }

    override fun unhold() {
        call.unhold()
    }

    override fun setMuted(muted: Boolean) {
        mute(muted)
    }
}

private object NoOpCallUiLauncher : CallUiLauncher {
    override fun launch(call: CallHandle) = Unit
}
