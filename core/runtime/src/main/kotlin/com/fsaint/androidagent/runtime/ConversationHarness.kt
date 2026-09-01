package com.fsaint.androidagent.runtime

import com.fsaint.androidagent.model.AgentEvent
import com.fsaint.androidagent.model.ScopedAgentSession
import com.fsaint.androidagent.model.ToolCall
import com.fsaint.androidagent.model.ToolResult
import com.fsaint.androidagent.policy.AgentContext
import com.fsaint.androidagent.policy.ScopedToolRouter

/** A model-facing request. The transcript is intentionally bounded by the harness. */
data class ConversationRequest(
    val session: ScopedAgentSession,
    val event: AgentEvent,
    val context: AgentContext,
    val userText: String,
    val transcript: ConversationTranscript = ConversationTranscript(),
)

sealed interface ConversationResponse {
    data class Tool(val call: ToolCall) : ConversationResponse
    data class Final(val text: String) : ConversationResponse
}

sealed interface ConversationTurn {
    data class AssistantTool(val call: ToolCall) : ConversationTurn
    data class ToolOutput(val call: ToolCall, val result: ToolResult<Any>) : ConversationTurn
    data class AssistantFinal(val text: String) : ConversationTurn
}

data class ConversationTranscript(val turns: List<ConversationTurn> = emptyList(), val nextTurn: Int = 0)

interface ConversationModel {
    suspend fun respond(request: ConversationRequest): ConversationResponse
}

interface ConversationCheckpointStore {
    suspend fun save(id: String, transcript: ConversationTranscript)
    suspend fun load(id: String): ConversationTranscript?
    suspend fun remove(id: String)
}

class InMemoryConversationCheckpointStore : ConversationCheckpointStore {
    private val entries = mutableMapOf<String, ConversationTranscript>()
    override suspend fun save(id: String, transcript: ConversationTranscript) { entries[id] = transcript }
    override suspend fun load(id: String): ConversationTranscript? = entries[id]
    override suspend fun remove(id: String) { entries.remove(id) }
}

enum class ConversationStopReason { FINAL_RESPONSE, TURN_LIMIT }

data class ConversationResult(
    val response: String?,
    val transcript: ConversationTranscript,
    val turns: Int,
    val toolCalls: List<ToolCall>,
    val stopReason: ConversationStopReason,
)

class ConversationHarness(
    private val model: ConversationModel,
    private val tools: ScopedToolRouter,
    private val checkpoints: ConversationCheckpointStore = InMemoryConversationCheckpointStore(),
    private val maxTurns: Int = MAX_TURNS,
    private val toolEffects: EventStore? = null,
) {
    init { require(maxTurns in 1..MAX_TURNS) }

    suspend fun run(request: ConversationRequest): ConversationResult = execute(request, request.transcript)

    suspend fun cancel(conversationId: String, transcript: ConversationTranscript) {
        checkpoints.save(conversationId, transcript.copy(nextTurn = transcript.nextTurn.coerceIn(0, maxTurns)))
    }

    suspend fun resume(request: ConversationRequest, conversationId: String): ConversationResult {
        val saved = checkpoints.load(conversationId) ?: return run(request)
        return try { execute(request.copy(transcript = saved), saved) } finally { checkpoints.remove(conversationId) }
    }

    private suspend fun execute(request: ConversationRequest, starting: ConversationTranscript): ConversationResult {
        var transcript = starting
        val calls = transcript.turns.filterIsInstance<ConversationTurn.AssistantTool>().map { it.call }.toMutableList()
        while (transcript.nextTurn < maxTurns) {
            val response = model.respond(request.copy(transcript = transcript))
            when (response) {
                is ConversationResponse.Final -> {
                    transcript = transcript.copy(
                        turns = transcript.turns + ConversationTurn.AssistantFinal(response.text),
                        nextTurn = transcript.nextTurn + 1,
                    )
                    return ConversationResult(response.text, transcript, transcript.nextTurn, calls, ConversationStopReason.FINAL_RESPONSE)
                }
                is ConversationResponse.Tool -> {
                    calls += response.call
                    val executionCall = response.call.withConversationRecipient(request)
                    val turn = transcript.nextTurn
                    val result: ToolResult<Any> = when (val effect = toolEffects?.reserveToolEffect(request.event.id, executionCall, turn)) {
                        is ToolEffectReservation.Completed -> effect.result
                        ToolEffectReservation.Pending -> ToolResult<Any>(false, error = com.fsaint.androidagent.model.ToolError.FAILED, recoverable = true)
                        ToolEffectReservation.Reserved, null -> {
                            // A capability must not be able to abort the whole conversation.
                            // Return failures to the model so it can explain or recover.
                            val executed = runCatching { tools.execute(request.session, executionCall) }
                                .getOrElse {
                                    ToolResult(false, error = com.fsaint.androidagent.model.ToolError.FAILED, recoverable = true)
                                }
                            toolEffects?.completeToolEffect(
                                request.event.id,
                                executionCall,
                                turn,
                                executed,
                            )
                            executed
                        }
                    }
                    transcript = transcript.copy(
                        turns = transcript.turns + ConversationTurn.AssistantTool(response.call) + ConversationTurn.ToolOutput(response.call, result),
                        nextTurn = transcript.nextTurn + 1,
                    )
                }
            }
        }
        return ConversationResult(null, transcript, transcript.nextTurn, calls, ConversationStopReason.TURN_LIMIT)
    }

    private companion object { const val MAX_TURNS = 8 }
}

private fun ToolCall.withConversationRecipient(request: ConversationRequest): ToolCall {
    if (name != "telegram.send_photo" || request.session.channel.uppercase() != "TELEGRAM") return this
    val recipient = request.event.payload["sender"]?.takeIf(String::isNotBlank) ?: request.event.source
    return if (recipient.isBlank() || arguments["chatId"].isNullOrBlank()) {
        copy(arguments = arguments + ("chatId" to recipient))
    } else this
}
