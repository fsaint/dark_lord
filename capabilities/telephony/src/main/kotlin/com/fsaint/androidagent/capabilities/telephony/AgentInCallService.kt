package com.fsaint.androidagent.capabilities.telephony

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.telecom.PhoneAccount
import android.telecom.Call
import android.telecom.InCallService
import android.telecom.VideoProfile
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
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

    private val calls = ProcessCallRepository()

    fun configure(eventSink: CallEventSink, uiLauncher: CallUiLauncher) {
        this.eventSink = eventSink
        this.uiLauncher = uiLauncher
    }

    internal fun eventPublisher(): CallEventPublisher = CallEventPublisher(eventSink)
    internal fun launcher(): CallUiLauncher = uiLauncher
    internal fun mutableCallRepository(): ProcessCallRepository = calls
    fun callRepository(): CallRepository = calls
}

class AgentInCallService(
    private val eventPublisher: CallEventPublisher = AgentInCallServiceDependencies.eventPublisher(),
    private val uiLauncher: CallUiLauncher = AgentInCallServiceDependencies.launcher(),
    private val callRepository: ProcessCallRepository = AgentInCallServiceDependencies.mutableCallRepository(),
) : InCallService() {
    private val callbacks = ConcurrentHashMap<String, Call.Callback>()

    override fun onBind(intent: Intent): IBinder = super.onBind(intent) ?: Binder()

    override fun onCallAdded(call: Call) {
        val handle = TelecomCallHandle(call, ::setMuted, normalizedTelephoneHandle(call))
        callRepository.add(handle)
        val callback = stateCallback(handle)
        callbacks[handle.id] = callback
        call.registerCallback(callback)
        publishAndLaunch(handle)
    }

    /** Testable boundary for a call supplied by Telecom. */
    fun onCallAdded(call: CallHandle) {
        callRepository.add(call)
        publishAndLaunch(call)
    }

    override fun onCallRemoved(call: Call) {
        val handle = TelecomCallHandle(call, ::setMuted, normalizedTelephoneHandle(call))
        callbacks.remove(handle.id)?.let(call::unregisterCallback)
        callRepository.remove(handle.id)
    }

    fun activeCalls(): List<ActiveCall> = callRepository.calls.value

    private fun stateCallback(handle: CallHandle): Call.Callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            callRepository.update(handle)
            eventPublisher.publishState(handle)
        }

        override fun onDetailsChanged(call: Call, details: Call.Details) {
            callRepository.update(handle)
            eventPublisher.publishState(handle)
        }
    }

    private fun publishAndLaunch(call: CallHandle) {
        eventPublisher.publishState(call)
        uiLauncher.launch(call)
    }

    private fun normalizedTelephoneHandle(call: Call): String? {
        val handle = call.details.handle ?: return null
        if (handle.scheme != PhoneAccount.SCHEME_TEL) return null
        val rawNumber = handle.schemeSpecificPart?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val networkCountry = getSystemService(TelephonyManager::class.java)
            ?.networkCountryIso
            ?.uppercase()
            ?.takeIf(String::isNotEmpty)
        return networkCountry?.let { PhoneNumberUtils.formatNumberToE164(rawNumber, it) } ?: rawNumber
    }
}

private class TelecomCallHandle(
    private val call: Call,
    private val mute: (Boolean) -> Unit,
    override val telephoneHandle: String?,
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
