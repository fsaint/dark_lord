package com.fsaint.androidagent.communications

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommunicationsReplySenderTest {
    @Test
    fun onlySmsTargetsReachSmsTransport() = runTest {
        val sent = mutableListOf<Pair<String, String>>()
        val sender = CommunicationsReplySender { recipient, text -> sent += recipient to text }

        sender.send("CALL", "unknown:telecom-call", "call reply")
        sender.send("NOTIFICATION", "com.example.mail", "notification reply")
        sender.send("SMS", "+14155550100", "sms reply")

        assertEquals(listOf("+14155550100" to "sms reply"), sent)
    }

    @Test
    fun blankSmsDestinationIsNotSent() = runTest {
        val sent = mutableListOf<Pair<String, String>>()
        val sender = CommunicationsReplySender { recipient, text -> sent += recipient to text }

        sender.send("SMS", "", "reply")

        assertTrue(sent.isEmpty())
    }
}
