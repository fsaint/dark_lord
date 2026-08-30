package com.fsaint.androidagent.capabilities.telephony

import android.telecom.Call
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fsaint.androidagent.model.AgentEvent
import com.fsaint.androidagent.model.ToolError
import org.junit.Assert.assertEquals
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
}

private class FakeCall(
    override val id: String = "call-1",
    override val state: Int = Call.STATE_ACTIVE,
    override val capabilities: Int = 0,
) : CallHandle {
    var holdCalls = 0

    override fun answer() = Unit
    override fun reject() = Unit
    override fun disconnect() = Unit
    override fun hold() {
        holdCalls += 1
    }
    override fun unhold() = Unit
    override fun setMuted(muted: Boolean) = Unit
}

private class RecordingCallEventSink : CallEventSink {
    val events = CopyOnWriteArrayList<AgentEvent>()

    override fun publish(event: AgentEvent) {
        events += event
    }
}
