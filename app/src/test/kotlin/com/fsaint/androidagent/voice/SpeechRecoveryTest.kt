package com.fsaint.androidagent.voice

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechRecoveryTest {
    @Test fun interruptionAfterTranscriptSchedulesIdleCleanup() = runTest {
        var shutdowns = 0
        val recognizer = object : RecognizerPort {
            override fun startListening() = Unit
            override fun stopListening() = Unit
            override fun cancel() = Unit
            override fun shutdown() { shutdowns++ }
        }
        val controller = PushToTalkController(recognizer, FakeTurns(true), FakeSpeaker(), backgroundScope)
        controller.pressed()
        controller.transcript("hello")
        runCurrent()
        controller.cancel()
        assertEquals(1, shutdowns)
    }
    @Test fun disconnectRetriesOnceAndRejectsLateResults() = runTest {
        val recognizer = RecordingRecognizer()
        val turns = FakeTurns(true)
        val speaker = FakeSpeaker()
        val controller = PushToTalkController(recognizer, turns, speaker, backgroundScope)
        controller.pressed()
        val old = controller.eventsForCurrentTurn()
        old.fail(VoiceTurnError.DISCONNECTED)
        assertEquals(VoiceTurnState.Recovering, controller.state.value)
        old.transcript("must not run")
        advanceTimeBy(301)
        runCurrent()
        old.ready()
        old.transcript("still stale")
        assertEquals(2, recognizer.calls.count { it == "start" })
        controller.eventsForCurrentTurn().fail(VoiceTurnError.DISCONNECTED)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, recognizer.calls.count { it == "start" })
        assertTrue(turns.dispatched.isEmpty())
        assertEquals(listOf(VoiceTurnError.DISCONNECTED.spokenMessage), speaker.spoken)
    }

    @Test fun readySpeechOrPartialPreventsRetry() = runTest {
        for (signal in listOf<(RecognizerEvents) -> Unit>({ it.ready() }, { it.speechStarted() }, { it.partialSpeech() })) {
            val recognizer = RecordingRecognizer()
            val controller = PushToTalkController(recognizer, FakeTurns(true), FakeSpeaker(), backgroundScope)
            controller.pressed()
            signal(controller.eventsForCurrentTurn())
            controller.fail(VoiceTurnError.DISCONNECTED)
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(1, recognizer.calls.count { it == "start" })
            assertEquals(VoiceTurnState.Error(VoiceTurnError.DISCONNECTED), controller.state.value)
        }
    }

    @Test fun releaseCancelAndReplacementCancelPendingRecovery() = runTest {
        for (action in listOf<(PushToTalkController) -> Unit>({ it.released() }, { it.cancel() }, { it.pressed() }, { it.shutdown() })) {
            val recognizer = RecordingRecognizer()
            val controller = PushToTalkController(recognizer, FakeTurns(true), FakeSpeaker(), backgroundScope)
            controller.pressed()
            controller.fail(VoiceTurnError.DISCONNECTED)
            action(controller)
            val starts = recognizer.calls.count { it == "start" }
            advanceTimeBy(301)
            runCurrent()
            assertEquals(starts, recognizer.calls.count { it == "start" })
        }
    }

    @Test fun readyCancelsReadinessDeadlineAndFinalResultDispatchesOnce() = runTest {
        val turns = FakeTurns(true)
        val controller = PushToTalkController(RecordingRecognizer(), turns, FakeSpeaker(), backgroundScope)
        controller.pressed()
        controller.ready()
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(VoiceTurnState.Listening, controller.state.value)
        controller.endOfSpeech()
        controller.transcript("hello")
        controller.transcript("duplicate")
        runCurrent()
        assertEquals(listOf("hello"), turns.dispatched)
    }

    @Test fun finalizationTimeoutHasDistinctError() = runTest {
        val controller = PushToTalkController(RecordingRecognizer(), FakeTurns(true), FakeSpeaker(), backgroundScope)
        controller.pressed()
        controller.ready()
        controller.endOfSpeech()
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(VoiceTurnState.Error(VoiceTurnError.RECOGNITION_TIMEOUT), controller.state.value)
    }
    @Test fun readinessHasBoundedDeadline() = runTest {
        val speaker = FakeSpeaker()
        val recognizer = RecordingRecognizer()
        val controller = PushToTalkController(recognizer, FakeTurns(true), speaker, backgroundScope)
        controller.pressed()
        advanceTimeBy(5_001)
        runCurrent()
        assertIs<VoiceTurnState.Error>(controller.state.value)
        assertEquals(1, recognizer.calls.count { it == "cancel" })
        assertEquals(1, speaker.spoken.size)
    }
}
