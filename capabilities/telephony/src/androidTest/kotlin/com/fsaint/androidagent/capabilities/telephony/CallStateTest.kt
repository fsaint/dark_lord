package com.fsaint.androidagent.capabilities.telephony

import android.telecom.Call
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fsaint.androidagent.model.AgentEvent
import com.fsaint.androidagent.model.ToolError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class CallStateTest {
    @Test
    fun unsupportedHoldIsReportedWithoutCallingTelecom() {
        val call = FakeCall(capabilities = 0)
        val controller = CallController()

        val result = controller.hold(call)

        assertEquals(ToolError.UNSUPPORTED, result.error)
        assertEquals(0, call.holdCalls)
    }

    @Test
    fun incomingCallPublishesRingingState() {
        val sink = RecordingCallEventSink()
        val service = AgentInCallService(CallEventPublisher(sink))

        service.onCallAdded(FakeCall(state = Call.STATE_RINGING))

        assertEquals("call.state", sink.events.single().type)
        assertEquals(Call.STATE_RINGING.toString(), sink.events.single().payload["state"])
    }

    @Test
    fun callEventUsesTelephoneHandleAsSourceAndKeepsOpaqueCallIdInPayload() {
        val sink = RecordingCallEventSink()
        val publisher = CallEventPublisher(sink, now = { 123L })

        publisher.publishState(
            FakeCall(
                id = "telecom:opaque-7",
                telephoneHandle = "+14155550100",
                state = Call.STATE_RINGING,
            ),
        )

        val event = sink.events.single()
        assertEquals("+14155550100", event.source)
        assertEquals("telecom:opaque-7", event.payload["callId"])
        assertEquals("+14155550100", event.payload["telephoneHandle"])
    }

    @Test
    fun repositoryRoutesSupportedControlsToTheLiveCallByOpaqueId() {
        val call = FakeCall(
            id = "telecom:opaque-8",
            state = Call.STATE_RINGING,
            capabilities = Call.Details.CAPABILITY_MUTE,
        )
        val repository = ProcessCallRepository()
        repository.add(call)

        assertTrue(repository.answer(call.id).success)
        assertTrue(repository.setMuted(call.id, true).success)
        assertEquals(1, call.answerCalls)
        assertEquals(listOf(true), call.muteCalls)
        assertTrue(repository.calls.value.single().muted)
    }

    @Test
    fun repositoryPublishesLiveStateAndRemovesFinishedCall() {
        val call = FakeCall(id = "telecom:opaque-9", state = Call.STATE_RINGING)
        val repository = ProcessCallRepository()
        repository.add(call)

        assertEquals(Call.STATE_RINGING, repository.calls.value.single().state)

        call.currentState = Call.STATE_ACTIVE
        repository.update(call)

        val active = repository.calls.value.single()
        assertEquals(Call.STATE_ACTIVE, active.state)
        assertFalse(active.canAnswer)
        assertTrue(active.canDisconnect)

        repository.remove(call.id)

        assertTrue(repository.calls.value.isEmpty())
        assertEquals(ToolError.NOT_FOUND, repository.disconnect(call.id).error)
        assertNull(repository.call(call.id))
    }

    @Test
    fun supportedHoldAndUnholdActionsAreExposedFromCurrentState() {
        val call = FakeCall(
            state = Call.STATE_ACTIVE,
            capabilities = Call.Details.CAPABILITY_HOLD,
        )
        val repository = ProcessCallRepository()
        repository.add(call)

        assertTrue(repository.calls.value.single().canHold)
        assertFalse(repository.calls.value.single().canUnhold)
        assertTrue(repository.hold(call.id).success)
        assertEquals(1, call.holdCalls)

        call.currentState = Call.STATE_HOLDING
        repository.update(call)

        assertFalse(repository.calls.value.single().canHold)
        assertTrue(repository.calls.value.single().canUnhold)
        assertTrue(repository.unhold(call.id).success)
        assertEquals(1, call.unholdCalls)
    }
}

private class FakeCall(
    override val id: String = "call-1",
    override val telephoneHandle: String? = "+14155550199",
    state: Int = Call.STATE_ACTIVE,
    override val capabilities: Int = 0,
) : CallHandle {
    var currentState: Int = state
    override val state: Int
        get() = currentState
    var answerCalls = 0
    var holdCalls = 0
    var unholdCalls = 0
    val muteCalls = mutableListOf<Boolean>()

    override fun answer() {
        answerCalls += 1
    }
    override fun reject() = Unit
    override fun disconnect() = Unit
    override fun hold() {
        holdCalls += 1
    }
    override fun unhold() {
        unholdCalls += 1
    }
    override fun setMuted(muted: Boolean) {
        muteCalls += muted
    }
}

private class RecordingCallEventSink : CallEventSink {
    val events = CopyOnWriteArrayList<AgentEvent>()

    override fun publish(event: AgentEvent) {
        events += event
    }
}
