package com.fsaint.androidagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PhotoConversationRunnerTest {
    @Test fun publishesCaptureProcessingAndAnswerBeforeSpeaking() = runTest {
        val events = mutableListOf<String>()
        PhotoConversationRunner(
            capture = { events += "capture"; "fresh-image" },
            analyze = { assertEquals("fresh-image", it); events += "model"; "A red mug." },
            publish = { events += it.phase.name },
            speak = { events += "speak:$it" },
        ).run()
        assertEquals(listOf("CAPTURING", "capture", "PROCESSING", "model", "COMPLETE", "speak:A red mug."), events)
    }

    @Test fun captureFailureDoesNotCallModelOrSpeakAndRedactsException() = runTest {
        val states = mutableListOf<PhotoConversationState>()
        PhotoConversationRunner<String>(
            capture = { error("secret-token") },
            analyze = { error("Must not reach model") },
            publish = { states += it },
            speak = { error("Must not speak an answer") },
        ).run()
        assertEquals(PhotoPhase.ERROR, states.last().phase)
        assertTrue(!states.last().text.contains("secret-token"))
    }

    @Test fun speechFailurePreservesCompletedAnswer() = runTest {
        val states = mutableListOf<PhotoConversationState>()
        PhotoConversationRunner(capture = { "image" }, analyze = { "A red mug." },
            publish = { states += it }, speak = { error("No TTS engine") }).run()
        assertEquals(PhotoPhase.COMPLETE, states.last().phase)
        assertEquals("A red mug.", states.last().text)
    }

    @Test fun modelTimeoutFinishesWithErrorRatherThanEndlessProgress() = runTest {
        val states = mutableListOf<PhotoConversationState>()
        PhotoConversationRunner(capture = { "image" }, analyze = { CompletableDeferred<String>().await() },
            publish = { states += it }, speak = {}, timeoutMillis = 100).run()
        assertEquals(PhotoPhase.ERROR, states.last().phase)
    }

    @Test fun cancellationPublishesInterruptedAndDoesNotSpeak() = runTest {
        val states = mutableListOf<PhotoConversationState>()
        var spoken = false
        val job = launch {
            PhotoConversationRunner(capture = { "image" }, analyze = { CompletableDeferred<String>().await() },
                publish = { states += it }, speak = { spoken = true }).run()
        }
        runCurrent()
        job.cancel(CancellationException("screen gone"))
        job.join()
        assertEquals(PhotoPhase.ERROR, states.last().phase)
        assertTrue(!spoken)
    }
}
