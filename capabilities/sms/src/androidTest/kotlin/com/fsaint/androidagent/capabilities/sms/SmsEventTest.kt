package com.fsaint.androidagent.capabilities.sms

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Telephony
import android.telephony.SubscriptionManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fsaint.androidagent.model.AgentEvent
import com.fsaint.androidagent.model.VerificationState
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals

@RunWith(AndroidJUnit4::class)
class SmsEventTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun smsDeliverCreatesOneEventForEachPdu() {
        val sink = RecordingSink()
        val receiver = SmsBroadcastReceiver(sink)

        receiver.onReceive(context, smsDeliverIntent(PDU_BATTERY, PDU_STATUS))

        assertEquals(2, sink.events.size)
        assertEquals("sms.received", sink.events[0].type)
        assertEquals("+14155550100", sink.events[0].source)
        assertEquals("+14155550100", sink.events[0].payload["sender"])
        assertEquals("battery", sink.events[0].payload["body"])
        assertEquals("7", sink.events[0].payload["subscriptionId"])
        assertEquals("status", sink.events[1].payload["body"])
    }

    @Test
    fun submittedSmsIsUnverifiedUntilCarrierDelivery() {
        val sender = SmsReplySender(
            context = context,
            sink = RecordingSink(),
            access = AllowedSmsAccess,
            transport = RecordingTransport,
        )

        val result = sender.send("+14155550100", "72%")

        assertEquals(true, result.success)
        assertEquals(VerificationState.UNVERIFIED, result.verification)
    }

    @Test
    fun carrierDeliveryIsTheOnlyVerifiedTransportEvent() {
        val sink = RecordingSink()
        val sender = SmsReplySender(
            context = context,
            sink = sink,
            access = AllowedSmsAccess,
            transport = object : SmsTransport {
                override fun send(
                    destination: String,
                    body: String,
                    subscriptionId: Int?,
                    sentIntent: android.app.PendingIntent,
                    deliveredIntent: android.app.PendingIntent,
                ) {
                    sentIntent.send(context, android.app.Activity.RESULT_OK, null)
                    deliveredIntent.send(context, android.app.Activity.RESULT_OK, null)
                }
            },
        )

        sender.send("+14155550100", "72%", subscriptionId = 7)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        assertEquals("UNVERIFIED", sink.events.single { it.type == "sms.sent" }.payload["verification"])
        assertEquals("VERIFIED", sink.events.single { it.type == "sms.delivered" }.payload["verification"])
        assertEquals("7", sink.events.single { it.type == "sms.delivered" }.payload["subscriptionId"])
    }

    private fun smsDeliverIntent(vararg pdus: ByteArray): Intent = Intent(Telephony.Sms.Intents.SMS_DELIVER_ACTION).apply {
        putExtras(Bundle().apply {
            putSerializable("pdus", pdus)
            putString("format", "3gpp")
            putInt(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, 7)
        })
    }
}

private class RecordingSink : SmsEventSink {
    val events = mutableListOf<AgentEvent>()
    override fun publish(event: AgentEvent) {
        events += event
    }
}

private object AllowedSmsAccess : SmsAccess {
    override fun canSend(): Boolean = true
}

private object RecordingTransport : SmsTransport {
    override fun send(destination: String, body: String, subscriptionId: Int?, sentIntent: android.app.PendingIntent, deliveredIntent: android.app.PendingIntent) = Unit
}

private val PDU_BATTERY = byteArrayOf(
    0x00, 0x00, 0x0B, 0x91.toByte(), 0x41, 0x51, 0x55, 0x05, 0x01, 0xF0.toByte(), 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x07, 0xE2.toByte(), 0x30, 0x9D.toByte(), 0x5E, 0x96.toByte(), 0xE7.toByte(), 0x01,
)

private val PDU_STATUS = byteArrayOf(
    0x00, 0x00, 0x0B, 0x91.toByte(), 0x41, 0x51, 0x55, 0x05, 0x01, 0xF0.toByte(), 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x06, 0x73, 0x7A, 0x98.toByte(), 0x5E, 0x9F.toByte(), 0xCF.toByte(), 0x01,
)
