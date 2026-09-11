package com.fsaint.androidagent.chat

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChatTasksTest {
    @Test fun busySendIsRejectedAndStoppingOneChatLeavesAnotherRunning() = runTest {
        val requests = mutableListOf<String>()
        val tasks = ChatTasks(backgroundScope) { id, text, selected ->
            requests += "$id:$text:$selected"
            CompletableDeferred<String>().await()
        }
        assertTrue(tasks.send("one", "Look", "photo1"))
        assertFalse(tasks.send("one", "duplicate", null))
        assertTrue(tasks.send("two", "Hello", null))
        runCurrent()
        tasks.stop("one")
        runCurrent()
        assertEquals(listOf("one:Look:photo1", "two:Hello:null"), requests)
        assertFalse(tasks.states.value.getValue("one").running)
        assertTrue(tasks.states.value.getValue("two").running)
    }
    @Test fun resultIsStoredByTurnRunnerWithoutAnySpeechDependency() = runTest {
        var text = ""
        val tasks = ChatTasks(backgroundScope) { _, message, _ -> text = message; "Saved response" }
        tasks.send("one", "hello", null)
        runCurrent()
        assertEquals("hello", text)
        assertFalse(tasks.states.value.getValue("one").running)
        assertEquals(null, tasks.states.value.getValue("one").error)
    }
}
