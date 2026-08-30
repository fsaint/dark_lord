package com.fsaint.androidagent.data

import android.util.Base64
import com.fsaint.androidagent.model.ToolCall
import com.fsaint.androidagent.runtime.ConversationCheckpointStore
import com.fsaint.androidagent.runtime.ConversationTranscript
import com.fsaint.androidagent.runtime.ConversationTurn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Durable transcript store backed by the encrypted Room database. */
class RoomConversationCheckpointStore(private val repository: DurableStateRepository) : ConversationCheckpointStore {
    private val mutex = Mutex()

    override suspend fun save(id: String, transcript: ConversationTranscript) = mutex.withLock {
        transcript.turns.forEachIndexed { index, turn ->
            repository.save(
                ConversationMessageEntity(
                    id = "$id:$index",
                    sessionId = id,
                    createdAtEpochMs = transcript.nextTurn.toLong() * 1_000 + index,
                    content = encode(turn),
                ),
            )
        }
    }

    override suspend fun load(id: String): ConversationTranscript? = mutex.withLock {
        val rows = repository.conversation(id)
        if (rows.isEmpty()) return@withLock null
        ConversationTranscript(rows.sortedBy { it.createdAtEpochMs }.mapNotNull { decode(it.content) }, rows.size)
    }

    override suspend fun remove(id: String) = Unit // checkpoints are retained as conversation history

    private fun encode(turn: ConversationTurn): ByteArray = when (turn) {
        is ConversationTurn.AssistantTool -> "A|${b64(turn.call.name)}|${b64(turn.call.arguments.entries.joinToString("&") { "${it.key}=${it.value}" })}"
        is ConversationTurn.ToolOutput -> "O|${b64(turn.call.name)}|${b64(turn.result.payload?.toString().orEmpty())}"
        is ConversationTurn.AssistantFinal -> "F|${b64(turn.text)}"
    }.toByteArray()

    private fun decode(bytes: ByteArray): ConversationTurn? = runCatching {
        val parts = bytes.toString(Charsets.UTF_8).split('|')
        val call = ToolCall(unb64(parts[1]), unb64(parts.getOrElse(2) { "" }).split('&').filter { it.contains('=') }.associate { it.substringBefore('=') to it.substringAfter('=') })
        when (parts[0]) {
            "A" -> ConversationTurn.AssistantTool(call)
            "O" -> ConversationTurn.ToolOutput(call, com.fsaint.androidagent.model.ToolResult(success = true, payload = unb64(parts.getOrElse(2) { "" })))
            "F" -> ConversationTurn.AssistantFinal(unb64(parts[1]))
            else -> null
        }
    }.getOrNull()

    private fun b64(value: String) = Base64.encodeToString(value.toByteArray(), Base64.NO_WRAP)
    private fun unb64(value: String) = Base64.decode(value, Base64.NO_WRAP).toString(Charsets.UTF_8)
}
