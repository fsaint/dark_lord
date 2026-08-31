package com.fsaint.androidagent.capabilities.sms

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.fsaint.androidagent.model.AgentEvent
import com.fsaint.androidagent.model.ToolError
import com.fsaint.androidagent.model.ToolResult
import com.fsaint.androidagent.model.VerificationState
import java.util.concurrent.atomic.AtomicInteger

class SmsReplySender internal constructor(
    private val context: Context,
    private val sink: SmsEventSink,
    private val access: SmsAccess,
    private val transport: SmsTransport,
) {
    constructor(context: Context, sink: SmsEventSink) : this(
        context = context,
        sink = sink,
        access = PlatformSmsAccess(context),
        transport = PlatformSmsTransport(context),
    )

    fun send(destination: String, body: String, subscriptionId: Int? = null): ToolResult<String> {
        android.util.Log.i("DarkLordSms", "reply destinationLength=${destination.length} destinationSuffix=${destination.takeLast(4)}")
        if (destination.isBlank() || destination == "unknown:sms") {
            return ToolResult(
                success = false,
                error = ToolError.UNSUPPORTED,
                recoverable = false,
            )
        }
        if (!access.canSend()) {
            return ToolResult(
                success = false,
                error = ToolError.PERMISSION_REQUIRED,
                recoverable = true,
            )
        }

        val submittedAt = System.currentTimeMillis()
        val submissionId = "sms:out:${subscriptionId ?: "default"}:$submittedAt:$destination"
        val resultReceivers = TransportResultReceivers(
            context = context,
            sink = sink,
            destination = destination,
            body = body,
            subscriptionId = subscriptionId,
            submissionId = submissionId,
        )
        return try {
            resultReceivers.register()
            transport.send(
                destination = destination,
                body = body,
                subscriptionId = subscriptionId,
                sentIntent = resultReceivers.sentIntent,
                deliveredIntent = resultReceivers.deliveredIntent,
            )
            ToolResult(
                success = true,
                payload = submissionId,
                verification = VerificationState.UNVERIFIED,
            )
        } catch (_: SecurityException) {
            resultReceivers.unregister()
            ToolResult(false, error = ToolError.PERMISSION_REQUIRED, recoverable = true, verification = VerificationState.FAILED)
        } catch (_: Exception) {
            resultReceivers.unregister()
            ToolResult(false, error = ToolError.DEVICE_BUSY, recoverable = true, verification = VerificationState.FAILED)
        }
    }
}

internal interface SmsAccess {
    fun canSend(): Boolean
}

private class PlatformSmsAccess(private val context: Context) : SmsAccess {
    override fun canSend(): Boolean {
        val roleManager = context.getSystemService(RoleManager::class.java)
        return roleManager?.isRoleHeld(RoleManager.ROLE_SMS) == true &&
            context.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
    }
}

internal interface SmsTransport {
    fun send(
        destination: String,
        body: String,
        subscriptionId: Int?,
        sentIntent: PendingIntent,
        deliveredIntent: PendingIntent,
    )
}

private class PlatformSmsTransport(private val context: Context) : SmsTransport {
    @Suppress("DEPRECATION")
    override fun send(
        destination: String,
        body: String,
        subscriptionId: Int?,
        sentIntent: PendingIntent,
        deliveredIntent: PendingIntent,
    ) {
        val manager = subscriptionId?.let { SmsManager.getDefault().createForSubscriptionId(it) } ?: SmsManager.getDefault()
        manager.sendTextMessage(destination, null, body, sentIntent, deliveredIntent)
    }
}

private class TransportResultReceivers(
    private val context: Context,
    private val sink: SmsEventSink,
    private val destination: String,
    private val body: String,
    private val subscriptionId: Int?,
    private val submissionId: String,
) {
    private val token = requestCode.incrementAndGet()
    private val sentAction = "${context.packageName}.SMS_SENT.$token"
    private val deliveredAction = "${context.packageName}.SMS_DELIVERED.$token"
    private var sentRegistered = false
    private var deliveredRegistered = false

    private val sentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            android.util.Log.i("DarkLordSms", "reply sent resultCode=$resultCode")
            publish("sms.sent", if (resultCode == Activity.RESULT_OK) VerificationState.UNVERIFIED else VerificationState.FAILED, resultCode)
            unregisterSent()
            if (resultCode != Activity.RESULT_OK) unregisterDelivered()
        }
    }

    private val deliveredReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            publish("sms.delivered", if (resultCode == Activity.RESULT_OK) VerificationState.VERIFIED else VerificationState.FAILED, resultCode)
            unregisterDelivered()
        }
    }

    val sentIntent: PendingIntent = pendingBroadcast(sentAction, token)
    val deliveredIntent: PendingIntent = pendingBroadcast(deliveredAction, token + 1)

    fun register() {
        ContextCompat.registerReceiver(context, sentReceiver, IntentFilter(sentAction), ContextCompat.RECEIVER_NOT_EXPORTED)
        sentRegistered = true
        ContextCompat.registerReceiver(context, deliveredReceiver, IntentFilter(deliveredAction), ContextCompat.RECEIVER_NOT_EXPORTED)
        deliveredRegistered = true
    }

    fun unregister() {
        unregisterSent()
        unregisterDelivered()
    }

    private fun pendingBroadcast(action: String, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(action).setPackage(context.packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun publish(type: String, verification: VerificationState, resultCode: Int) {
        val occurredAt = System.currentTimeMillis()
        sink.publish(
            AgentEvent(
                id = "$submissionId:$type:$occurredAt",
                type = type,
                source = destination,
                occurredAtEpochMs = occurredAt,
                payload = mapOf(
                    "destination" to destination,
                    "body" to body,
                    "subscriptionId" to (subscriptionId?.toString() ?: ""),
                    "resultCode" to resultCode.toString(),
                    "verification" to verification.name,
                ),
            ),
        )
    }

    private fun unregisterSent() {
        if (sentRegistered) {
            context.unregisterReceiver(sentReceiver)
            sentRegistered = false
        }
    }

    private fun unregisterDelivered() {
        if (deliveredRegistered) {
            context.unregisterReceiver(deliveredReceiver)
            deliveredRegistered = false
        }
    }

    private companion object {
        val requestCode = AtomicInteger(10_000)
    }
}
