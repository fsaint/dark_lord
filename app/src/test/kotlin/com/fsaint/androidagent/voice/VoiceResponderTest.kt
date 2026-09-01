package com.fsaint.androidagent.voice

import com.fsaint.androidagent.runtime.ReplySender
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceResponderTest {
    private val speaker = FakeSpeaker()
    private val fallback = RecordingReplySender()

    @Test
    fun voiceChannelPushesReplyToController() = runTest {
        val controller = PushToTalkController(RecordingRecognizer(), FakeTurns(true), speaker, backgroundScope)
        val responder = VoiceResponder(controller, fallback)

        responder.send("VOICE", "voice", "Battery is at 72 percent.")

        assertEquals(VoiceTurnState.Responding("Battery is at 72 percent."), controller.state.value)
        assertEquals(listOf("Battery is at 72 percent."), speaker.spoken)
        assertTrue(fallback.sent.isEmpty())
    }

    @Test
    fun voiceChannelMatchIsCaseInsensitive() = runTest {
        val controller = PushToTalkController(RecordingRecognizer(), FakeTurns(true), speaker, backgroundScope)
        val responder = VoiceResponder(controller, fallback)

        responder.send("voice", "voice", "hi")

        assertEquals(listOf("hi"), speaker.spoken)
    }

    @Test
    fun otherChannelsFallThroughUntouched() = runTest {
        val controller = PushToTalkController(RecordingRecognizer(), FakeTurns(true), speaker, backgroundScope)
        val responder = VoiceResponder(controller, fallback)

        responder.send("TELEGRAM", "10", "hello")
        responder.send("SMS", "+14155550123", "hello")

        assertEquals(listOf("TELEGRAM" to "10", "SMS" to "+14155550123"), fallback.sent.map { it.first to it.second })
        assertEquals(VoiceTurnState.Idle, controller.state.value)
        assertTrue(speaker.spoken.isEmpty())
    }

    @Test
    fun blankVoiceReplyIsNotSpoken() = runTest {
        val controller = PushToTalkController(RecordingRecognizer(), FakeTurns(true), speaker, backgroundScope)
        val responder = VoiceResponder(controller, fallback)

        responder.send("VOICE", "voice", "   ")

        assertEquals(VoiceTurnState.Idle, controller.state.value)
        assertTrue(speaker.spoken.isEmpty())
    }

    private class RecordingReplySender : ReplySender {
        val sent = mutableListOf<Triple<String, String, String>>()
        override suspend fun send(channel: String, recipient: String, text: String) {
            sent += Triple(channel, recipient, text)
        }
    }
}
