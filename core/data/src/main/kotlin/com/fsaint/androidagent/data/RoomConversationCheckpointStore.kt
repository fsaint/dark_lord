package com.fsaint.androidagent.data

import android.util.Base64
import com.fsaint.androidagent.model.ToolCall
import com.fsaint.androidagent.runtime.ConversationCheckpointStore
import com.fsaint.androidagent.runtime.ConversationTranscript
import com.fsaint.androidagent.runtime.ConversationTurn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

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
                    content = encode(turn, transcript.nextTurn),
                ),
            )
        }
    }

    override suspend fun load(id: String): ConversationTranscript? = mutex.withLock {
        val rows = repository.conversation(id)
        if (rows.isEmpty()) return@withLock null
        val nextTurn = rows.mapNotNull { row -> runCatching { Json.parseToJsonElement(row.content.toString(Charsets.UTF_8)).jsonObject["nextTurn"]?.jsonPrimitive?.int }.getOrNull() }.maxOrNull() ?: rows.size
        ConversationTranscript(rows.sortedBy { it.createdAtEpochMs }.mapNotNull { decode(it.content) }, nextTurn)
    }

    override suspend fun remove(id: String) = Unit // checkpoints are retained as conversation history

    private fun encode(turn: ConversationTurn, nextTurn: Int): ByteArray = buildJsonObject {
        put("version", 2); put("nextTurn", nextTurn)
        val call = when (turn) { is ConversationTurn.AssistantTool -> turn.call; is ConversationTurn.ToolOutput -> turn.call; else -> null }
        if (call != null) { put("name", call.name); put("arguments", buildJsonObject { call.arguments.forEach { (key, value) -> put(key, value) } }) }
        when (turn) {
            is ConversationTurn.AssistantTool -> put("type", "A")
            is ConversationTurn.AssistantFinal -> { put("type", "F"); put("text", turn.text) }
            is ConversationTurn.ToolOutput -> {
                put("type", "O"); put("payload", turn.result.payload?.toString()); put("success", turn.result.success)
                put("error", turn.result.error?.name); put("recoverable", turn.result.recoverable); put("verification", turn.result.verification.name)
            }
        }
    }.toString().toByteArray()

    private fun decode(bytes: ByteArray): ConversationTurn? = runCatching {
        if (bytes.toString(Charsets.UTF_8).startsWith("{")) {
            val json = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            require(json["version"]?.jsonPrimitive?.int == 2)
            val call = ToolCall(json["name"]?.jsonPrimitive?.content.orEmpty(), (json["arguments"] as? JsonObject).orEmpty().mapValues { it.value.jsonPrimitive.content })
            return@runCatching when (json["type"]!!.jsonPrimitive.content) {
                "A" -> ConversationTurn.AssistantTool(call)
                "F" -> ConversationTurn.AssistantFinal(json["text"]!!.jsonPrimitive.content)
                "O" -> ConversationTurn.ToolOutput(call, com.fsaint.androidagent.model.ToolResult(
                    success = json["success"]!!.jsonPrimitive.boolean, payload = json["payload"]?.jsonPrimitive?.contentOrNull,
                    error = json["error"]?.jsonPrimitive?.contentOrNull?.let { com.fsaint.androidagent.model.ToolError.valueOf(it) },
                    recoverable = json["recoverable"]!!.jsonPrimitive.boolean,
                    verification = com.fsaint.androidagent.model.VerificationState.valueOf(json["verification"]!!.jsonPrimitive.content)))
                else -> null
            }
        }
        val parts = bytes.toString(Charsets.UTF_8).split('|')
        val call = ToolCall(unb64(parts[1]), unb64(parts.getOrElse(2) { "" }).split('&').filter { it.contains('=') }.associate { it.substringBefore('=') to it.substringAfter('=') })
        when (parts[0]) {
            "A" -> ConversationTurn.AssistantTool(call)
            "O" -> ConversationTurn.ToolOutput(call, com.fsaint.androidagent.model.ToolResult(success = true, payload = unb64(parts.getOrElse(2) { "" })))
            "F" -> ConversationTurn.AssistantFinal(unb64(parts[1]))
            else -> null
        }
    }.getOrNull()

    private fun unb64(value: String) = Base64.decode(value, Base64.NO_WRAP).toString(Charsets.UTF_8)
}
