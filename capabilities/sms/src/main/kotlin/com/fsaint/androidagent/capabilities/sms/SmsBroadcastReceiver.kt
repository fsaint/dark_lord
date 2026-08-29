package com.fsaint.androidagent.capabilities.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
import com.fsaint.androidagent.model.AgentEvent

interface SmsEventSink {
    fun publish(event: AgentEvent)
}

class SmsBroadcastReceiver(
    private val sink: SmsEventSink = NoOpSmsEventSink,
) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in INBOUND_SMS_ACTIONS) return

        val subscriptionId = intent.subscriptionIdOrNull()
        Telephony.Sms.Intents.getMessagesFromIntent(intent).forEach { message ->
            val sender = message.originatingAddress ?: return@forEach
            val timestamp = message.timestampMillis
            sink.publish(
                AgentEvent(
                    id = inboundEventId(subscriptionId, timestamp, sender),
                    type = "sms.received",
                    source = sender,
                    occurredAtEpochMs = timestamp,
                    payload = mapOf(
                        "sender" to sender,
                        "body" to (message.messageBody ?: ""),
                        "subscriptionId" to (subscriptionId?.toString() ?: ""),
                    ),
                ),
            )
        }
    }
}

internal fun Intent.subscriptionIdOrNull(): Int? =
    getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        .takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }

internal fun inboundEventId(subscriptionId: Int?, timestamp: Long, address: String): String =
    "sms:${subscriptionId ?: "default"}:$timestamp:$address"

private object NoOpSmsEventSink : SmsEventSink {
    override fun publish(event: AgentEvent) = Unit
}

private val INBOUND_SMS_ACTIONS = setOf(
    Telephony.Sms.Intents.SMS_DELIVER_ACTION,
    Telephony.Sms.Intents.SMS_RECEIVED_ACTION,
)
