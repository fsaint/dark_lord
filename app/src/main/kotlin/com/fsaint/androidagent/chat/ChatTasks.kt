package com.fsaint.androidagent.chat

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ChatTaskState(val running: Boolean, val error: String? = null)

/** App-owned text requests continue while navigating between chats; never automatically speak. */
class ChatTasks(private val scope: CoroutineScope, private val run: suspend (String, String, String?) -> String) {
    private val jobs = mutableMapOf<String, Job>()
    private val current = MutableStateFlow<Map<String, ChatTaskState>>(emptyMap())
    val states = current.asStateFlow()

    @Synchronized fun send(chatId: String, text: String, selectedPhoto: String?): Boolean {
        if (text.isBlank() || text.length > 16_384 || current.value[chatId]?.running == true) return false
        current.value = current.value + (chatId to ChatTaskState(true))
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var error: String? = null
            try { run(chatId, text, selectedPhoto) }
            catch (cancelled: CancellationException) { error = "Interrupted"; throw cancelled }
            catch (_: Exception) { error = "Could not finish this request. Check setup or connectivity and try again." }
            finally { synchronized(this@ChatTasks) { jobs.remove(chatId); current.value = current.value + (chatId to ChatTaskState(false, error)) } }
        }
        jobs[chatId] = job
        job.start()
        return true
    }
    @Synchronized fun stop(chatId: String) { jobs[chatId]?.cancel() }
}
