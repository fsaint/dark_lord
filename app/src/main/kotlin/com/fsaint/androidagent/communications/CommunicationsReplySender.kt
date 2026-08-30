package com.fsaint.androidagent.communications

import com.fsaint.androidagent.runtime.ReplySender

class CommunicationsReplySender(
    private val sendSms: suspend (recipient: String, text: String) -> Unit,
) : ReplySender {
    override suspend fun send(channel: String, recipient: String, text: String) {
        if (channel.equals("SMS", ignoreCase = true) && recipient.isNotBlank()) {
            sendSms(recipient, text)
        }
    }
}
