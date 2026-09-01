package com.fsaint.androidagent.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PushToTalkControllerTest {
    private val recognizer = RecordingRecognizer()
    private val speaker = FakeSpeaker()

    private fun TestScope.controller(
        ownerConfigured: Boolean = true,
        turns: FakeTurns = FakeTurns(ownerConfigured),
        scope: CoroutineScope = backgroundScope,
    ) = PushToTalkController(recognizer, turns, speaker, scope, finalizeTimeoutMs = 3_000, replyTimeoutMs = 60_000)

    @Test
    fun pressedStartsListeningAndRecognizer() = runTest {
        val controller = controller()

        controller.pressed()

        assertEquals(VoiceTurnState.Listening, controller.state.value)
        assertEquals(listOf("start"), recognizer.calls)
    }

    @Test
    fun releasedWhileListeningFinalizesAndStopsListening() = runTest {
        val controller = controller()
        controller.pressed()

        controller.released()

        assertEquals(VoiceTurnState.Finalizing, controller.state.value)
        assertEquals(listOf("start", "stop"), recognizer.calls)
    }

    @Test
    fun endOfSpeechWhileListeningFinalizes() = runTest {
        val controller = controller()
        controller.pressed()

        controller.endOfSpeech()

        assertEquals(VoiceTurnState.Finalizing, controller.state.value)
        assertEquals(listOf("start", "stop"), recognizer.calls)
    }

    @Test
    fun tapToSendWhileListeningFinalizes() = runTest {
        val controller = controller()
        controller.pressed()

        controller.tapToSend()

        assertEquals(VoiceTurnState.Finalizing, controller.state.value)
        assertEquals(listOf("start", "stop"), recognizer.calls)
    }

    @Test
    fun secondEndSignalIsNoOp() = runTest {
        val controller = controller()
        controller.pressed()
        controller.released()

        controller.endOfSpeech()
        controller.tapToSend()

        assertEquals(VoiceTurnState.Finalizing, controller.state.value)
        assertEquals(listOf("start", "stop"), recognizer.calls)
    }

    @Test
    fun transcriptMovesToThinkingAndDispatches() = runTest {
        val turns = FakeTurns(true)
        val controller = controller(turns = turns)
        controller.pressed()
        controller.released()

        controller.transcript("what is my battery")
        runCurrent()

        assertEquals(VoiceTurnState.Thinking, controller.state.value)
        assertEquals(listOf("what is my battery"), turns.dispatched)
    }

    @Test
    fun transcriptBeforeAnyEndSignalIsAccepted() = runTest {
        val turns = FakeTurns(true)
        val controller = controller(turns = turns)
        controller.pressed()

        controller.transcript("hello")
        runCurrent()

        assertEquals(VoiceTurnState.Thinking, controller.state.value)
        assertEquals(listOf("hello"), turns.dispatched)
    }

    @Test
    fun blankTranscriptReportsNoSpeech() = runTest {
        val controller = controller()
        controller.pressed()
        controller.released()

        controller.transcript("   ")

        assertEquals(VoiceTurnState.Error(VoiceTurnError.NO_SPEECH), controller.state.value)
        assertEquals(listOf(VoiceTurnError.NO_SPEECH.spokenMessage), speaker.spoken)
    }

    @Test
    fun finalizeTimeoutCancelsRecognizerAndSpeaksNoSpeech() = runTest {
        val controller = controller()
        controller.pressed()
        controller.released()

        advanceTimeBy(3_001)
        runCurrent()

        assertEquals(VoiceTurnState.Error(VoiceTurnError.NO_SPEECH), controller.state.value)
        assertEquals(listOf("start", "stop", "cancel"), recognizer.calls)
        assertEquals(listOf(VoiceTurnError.NO_SPEECH.spokenMessage), speaker.spoken)
    }

    @Test
    fun transcriptCancelsFinalizeTimeout() = runTest {
        val controller = controller()
        controller.pressed()
        controller.released()
        controller.transcript("hello")
        runCurrent()

        advanceTimeBy(3_001)
        runCurrent()

        assertEquals(VoiceTurnState.Thinking, controller.state.value)
        assertTrue(speaker.spoken.isEmpty())
    }

    @Test
    fun noOwnerSpeaksSetupMessage() = runTest {
        val controller = controller(ownerConfigured = false)
        controller.pressed()
        controller.released()

        controller.transcript("hello")
        runCurrent()

        assertEquals(VoiceTurnState.Error(VoiceTurnError.NO_OWNER), controller.state.value)
        assertEquals(listOf(VoiceTurnError.NO_OWNER.spokenMessage), speaker.spoken)
    }

    @Test
    fun replyReadyRespondsThenIdleAfterSpeechDone() = runTest {
        val controller = controller()
        controller.pressed()
        controller.released()
        controller.transcript("hello")
        runCurrent()

        controller.replyReady("Hi there")

        assertEquals(VoiceTurnState.Responding("Hi there"), controller.state.value)
        assertEquals(listOf("Hi there"), speaker.spoken)

        speaker.completeLast()

        assertEquals(VoiceTurnState.Idle, controller.state.value)
    }

    @Test
    fun replyTimeoutSpeaksTimeoutAndLateReplyIsStillSpoken() = runTest {
        val controller = controller()
        controller.pressed()
        controller.released()
        controller.transcript("hello")
        runCurrent()

        advanceTimeBy(60_001)
        runCurrent()

        assertEquals(VoiceTurnState.Error(VoiceTurnError.TIMEOUT), controller.state.value)
        assertEquals(listOf(VoiceTurnError.TIMEOUT.spokenMessage), speaker.spoken)
        speaker.completeLast()
        assertEquals(VoiceTurnState.Idle, controller.state.value)

        controller.replyReady("Late answer")

        assertEquals(VoiceTurnState.Responding("Late answer"), controller.state.value)
        assertEquals(listOf(VoiceTurnError.TIMEOUT.spokenMessage, "Late answer"), speaker.spoken)
    }

    @Test
    fun replyReadyCancelsReplyTimeout() = runTest {
        val controller = controller()
        controller.pressed()
        controller.released()
        controller.transcript("hello")
        runCurrent()
        controller.replyReady("Hi")
        speaker.completeLast()

        advanceTimeBy(60_001)
        runCurrent()

        assertEquals(VoiceTurnState.Idle, controller.state.value)
        assertEquals(listOf("Hi"), speaker.spoken)
    }

    @Test
    fun pressWhileListeningFinalizingOrThinkingIsIgnored() = runTest {
        val controller = controller()
        controller.pressed()
        controller.pressed()
        assertEquals(listOf("start"), recognizer.calls)

        controller.released()
        controller.pressed()
        assertEquals(VoiceTurnState.Finalizing, controller.state.value)

        controller.transcript("hello")
        runCurrent()
        controller.pressed()
        assertEquals(VoiceTurnState.Thinking, controller.state.value)
        assertEquals(listOf("start", "stop"), recognizer.calls)
    }

    @Test
    fun pressWhileRespondingStopsSpeechAndStartsNewTurn() = runTest {
        val controller = controller()
        controller.replyReady("Long answer")

        controller.pressed()

        assertEquals(1, speaker.stops)
        assertEquals(VoiceTurnState.Listening, controller.state.value)
        assertEquals(listOf("start"), recognizer.calls)

        // The interrupted utterance reports completion afterwards; the new turn must not be reset.
        speaker.completeLast()
        assertEquals(VoiceTurnState.Listening, controller.state.value)
    }

    @Test
    fun releasedWhileThinkingOrRespondingIsIgnored() = runTest {
        val controller = controller()
        controller.pressed()
        controller.released()
        controller.transcript("hello")
        runCurrent()

        controller.released()
        assertEquals(VoiceTurnState.Thinking, controller.state.value)

        controller.replyReady("Hi")
        controller.released()
        assertEquals(VoiceTurnState.Responding("Hi"), controller.state.value)
        assertEquals(listOf("start", "stop"), recognizer.calls)
    }

    @Test
    fun recognizerErrorSpeaksDidNotCatchAndReturnsToIdle() = runTest {
        val controller = controller()
        controller.pressed()

        controller.fail(VoiceTurnError.RECOGNIZER)

        assertEquals(VoiceTurnState.Error(VoiceTurnError.RECOGNIZER), controller.state.value)
        assertEquals(listOf(VoiceTurnError.RECOGNIZER.spokenMessage), speaker.spoken)
        speaker.completeLast()
        assertEquals(VoiceTurnState.Idle, controller.state.value)
    }

    @Test
    fun recognizerErrorAfterTurnEndedIsIgnored() = runTest {
        val controller = controller()
        controller.pressed()
        controller.released()
        advanceTimeBy(3_001)
        runCurrent()
        assertEquals(VoiceTurnState.Error(VoiceTurnError.NO_SPEECH), controller.state.value)

        // The cancelled recognizer reports its own error afterwards; it must not speak a second message.
        controller.fail(VoiceTurnError.RECOGNIZER)
        assertEquals(VoiceTurnState.Error(VoiceTurnError.NO_SPEECH), controller.state.value)

        speaker.completeLast()
        controller.fail(VoiceTurnError.NO_SPEECH)
        assertEquals(VoiceTurnState.Idle, controller.state.value)
        assertEquals(listOf(VoiceTurnError.NO_SPEECH.spokenMessage), speaker.spoken)
    }

    @Test
    fun recognizerErrorWhileThinkingIsIgnored() = runTest {
        val controller = controller()
        controller.pressed()
        controller.transcript("hello")
        runCurrent()

        controller.fail(VoiceTurnError.RECOGNIZER)

        assertEquals(VoiceTurnState.Thinking, controller.state.value)
        assertTrue(speaker.spoken.isEmpty())
    }

    @Test
    fun failMicrophonePermissionSpeaksPrompt() = runTest {
        val controller = controller()

        controller.fail(VoiceTurnError.MICROPHONE_PERMISSION)

        assertEquals(VoiceTurnState.Error(VoiceTurnError.MICROPHONE_PERMISSION), controller.state.value)
        assertEquals(listOf(VoiceTurnError.MICROPHONE_PERMISSION.spokenMessage), speaker.spoken)
    }

    @Test
    fun pressAfterErrorStartsNewTurn() = runTest {
        val controller = controller()
        controller.fail(VoiceTurnError.NO_SPEECH)

        controller.pressed()

        assertEquals(VoiceTurnState.Listening, controller.state.value)
        assertEquals(listOf("start"), recognizer.calls)
    }

    @Test
    fun stateFlowEmitsEveryTransition() = runTest {
        val controller = controller()
        val seen = mutableListOf<VoiceTurnState>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { controller.state.toList(seen) }

        controller.pressed()
        controller.released()
        controller.transcript("hello")
        runCurrent()
        controller.replyReady("Hi")
        speaker.completeLast()
        collector.cancel()

        assertEquals(
            listOf(
                VoiceTurnState.Idle,
                VoiceTurnState.Listening,
                VoiceTurnState.Finalizing,
                VoiceTurnState.Thinking,
                VoiceTurnState.Responding("Hi"),
                VoiceTurnState.Idle,
            ),
            seen,
        )
        assertIs<VoiceTurnState.Idle>(controller.state.value)
    }
}

internal class RecordingRecognizer : RecognizerPort {
    val calls = mutableListOf<String>()
    override fun startListening() { calls += "start" }
    override fun stopListening() { calls += "stop" }
    override fun cancel() { calls += "cancel" }
}

internal class FakeTurns(private val ownerConfigured: Boolean) : TurnDispatcher {
    val dispatched = mutableListOf<String>()
    override suspend fun dispatch(transcript: String): Boolean {
        dispatched += transcript
        return ownerConfigured
    }
}

internal class FakeSpeaker : Speaker {
    val spoken = mutableListOf<String>()
    var stops = 0
    private val pending = mutableListOf<() -> Unit>()

    override fun speak(text: String, onDone: () -> Unit) {
        spoken += text
        pending += onDone
    }

    override fun stop() { stops += 1 }
    override fun shutdown() = Unit

    fun completeLast() {
        val done = pending.removeLastOrNull() ?: error("nothing is being spoken")
        done()
    }
}
