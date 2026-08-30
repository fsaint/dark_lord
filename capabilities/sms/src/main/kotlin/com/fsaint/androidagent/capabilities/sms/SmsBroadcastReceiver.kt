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

/** Process-wide application wiring used when Android creates the manifest receiver. */
object SmsBroadcastReceiverDependencies {
    @Volatile
    private var eventSink: SmsEventSink = NoOpSmsEventSink

    fun configure(eventSink: SmsEventSink) {
        this.eventSink = eventSink
    }

    internal fun sink(): SmsEventSink = eventSink
}

class SmsBroadcastReceiver(
    private val sink: SmsEventSink = SmsBroadcastReceiverDependencies.sink(),
) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in INBOUND_SMS_ACTIONS) return

        val subscriptionId = intent.subscriptionIdOrNull()
        Telephony.Sms.Intents.getMessagesFromIntent(intent).forEachIndexed { index, message ->
            val sender = message?.originatingAddress.orEmpty()
            val source = sender.ifBlank { UNKNOWN_SMS_SOURCE }
            val timestamp = message?.timestampMillis ?: 0L
            sink.publish(
                AgentEvent(
                    id = inboundEventId(subscriptionId, timestamp, source, index),
                    type = "sms.received",
                    source = source,
                    occurredAtEpochMs = timestamp,
                    payload = mapOf(
                        "sender" to sender,
                        "body" to message?.messageBody.orEmpty(),
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

internal fun inboundEventId(subscriptionId: Int?, timestamp: Long, source: String, pduIndex: Int): String =
    "sms:${subscriptionId ?: "default"}:$timestamp:$source:$pduIndex"

private object NoOpSmsEventSink : SmsEventSink {
    override fun publish(event: AgentEvent) = Unit
}

private val INBOUND_SMS_ACTIONS = setOf(
    Telephony.Sms.Intents.SMS_DELIVER_ACTION,
    Telephony.Sms.Intents.SMS_RECEIVED_ACTION,
)

private const val UNKNOWN_SMS_SOURCE = "unknown:sms"
