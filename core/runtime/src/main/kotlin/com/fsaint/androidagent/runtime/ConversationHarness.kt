package com.fsaint.androidagent.runtime

import com.fsaint.androidagent.model.AgentEvent
import com.fsaint.androidagent.model.ScopedAgentSession
import com.fsaint.androidagent.model.ToolCall
import com.fsaint.androidagent.model.ToolResult
import com.fsaint.androidagent.policy.AgentContext
import com.fsaint.androidagent.policy.ScopedToolRouter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** A model-facing request. The transcript is intentionally bounded by the harness. */
data class ConversationRequest(
    val session: ScopedAgentSession,
    val event: AgentEvent,
    val context: AgentContext,
    val userText: String,
    val transcript: ConversationTranscript = ConversationTranscript(),
    val image: ConversationImage? = null,
    val priorMessages: List<PriorMessage> = emptyList(),
    val chatImages: List<ConversationImage> = emptyList(),
    val historyNotice: String = "",
    val onToolResult: (suspend (Int, ToolCall, ToolResult<Any>) -> Unit)? = null,
    val groundingNotice: String = "",
)

/** A bounded image captured or selected by an explicit foreground user action. */
data class ConversationImage(
    val mimeType: String,
    val bytes: ByteArray,
    val label: String? = null,
) {
    init {
        require(mimeType.startsWith("image/")) { "Conversation images must use an image MIME type" }
        require(bytes.isNotEmpty()) { "Conversation images must not be empty" }
    }
}

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

enum class ConversationStopReason { FINAL_RESPONSE, TURN_LIMIT, CAPABILITY_UNVERIFIED }

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
    private val extension: ConversationToolExtension? = null,
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

    private suspend fun execute(original: ConversationRequest, starting: ConversationTranscript): ConversationResult {
        val remote = try { extension?.prepare(original.session) ?: ConversationToolSnapshot() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ConversationToolSnapshot(inventory = listOf("MCP discovery unavailable. Phone tools remain available.")) }
        val definitions = remote.definitions.entries.take((128 - original.context.resources.size).coerceAtLeast(0)).associate { it.toPair() }
        val request = original.copy(context = original.context.copy(remoteTools = definitions,
            mcpInventory = remote.inventory + if (definitions.size < remote.definitions.size) listOf("Some MCP tools were omitted to fit the model tool limit.") else emptyList()))
        var transcript = starting
        var groundingNotice = ""
        var correctedAccessDenial = false
        val calls = transcript.turns.filterIsInstance<ConversationTurn.AssistantTool>().map { it.call }.toMutableList()
        while (transcript.nextTurn < maxTurns) {
            currentCoroutineContext().ensureActive()
            var response = model.respond(request.copy(transcript = transcript, groundingNotice = groundingNotice,
                priorMessages = if (correctedAccessDenial) CapabilityGrounding.correctionHistory(request.priorMessages) else request.priorMessages))
            currentCoroutineContext().ensureActive()
            if (response is ConversationResponse.Final && definitions.keys.any { it != MCP_INVENTORY_TOOL } &&
                CapabilityGrounding.unverifiedDenial(response.text, transcript)) {
                if (correctedAccessDenial) {
                    transcript = transcript.copy(turns = transcript.turns + ConversationTurn.AssistantFinal(CapabilityGrounding.UNVERIFIED), nextTurn = transcript.nextTurn + 1)
                    return ConversationResult(CapabilityGrounding.UNVERIFIED, transcript, transcript.nextTurn, calls, ConversationStopReason.CAPABILITY_UNVERIFIED)
                }
                correctedAccessDenial = true
                groundingNotice = CapabilityGrounding.CORRECTION
                // Only app-backed read-only inventory is automatic. All task actions still require
                // model selection, the original user request, and the existing scope/effect checks.
                if (MCP_INVENTORY_TOOL in definitions && transcript.turns.filterIsInstance<ConversationTurn.ToolOutput>().none { it.call.name == MCP_INVENTORY_TOOL }) {
                    response = ConversationResponse.Tool(ToolCall(MCP_INVENTORY_TOOL))
                } else {
                    transcript = transcript.copy(nextTurn = transcript.nextTurn + 1)
                    continue
                }
            }
            when (response) {
                is ConversationResponse.Final -> {
                    transcript = transcript.copy(
                        turns = transcript.turns + ConversationTurn.AssistantFinal(response.text),
                        nextTurn = transcript.nextTurn + 1,
                    )
                    return ConversationResult(response.text, transcript, transcript.nextTurn, calls, ConversationStopReason.FINAL_RESPONSE)
                }
                is ConversationResponse.Tool -> {
                    val priorResult = transcript.turns.asReversed()
                        .filterIsInstance<ConversationTurn.ToolOutput>()
                        .firstOrNull { it.call == response.call }
                    if (priorResult != null && !priorResult.result.success) {
                        val message = if (priorResult.result.success) {
                            "I already completed ${response.call.name} with that request."
                        } else {
                            "I couldn't complete ${response.call.name}: ${priorResult.result.error?.name ?: "the tool reported an error"}."
                        }
                        transcript = transcript.copy(
                            turns = transcript.turns + ConversationTurn.AssistantFinal(message),
                            nextTurn = transcript.nextTurn + 1,
                        )
                        return ConversationResult(message, transcript, transcript.nextTurn, calls, ConversationStopReason.FINAL_RESPONSE)
                    }
                    calls += response.call
                    currentCoroutineContext().ensureActive()
                    val executionCall = response.call.withConversationRecipient(request)
                    val turn = transcript.nextTurn
                    val result: ToolResult<Any> = when (val effect = toolEffects?.reserveToolEffect(request.event.id, executionCall, turn)) {
                        is ToolEffectReservation.Completed -> effect.result
                        ToolEffectReservation.Pending -> ToolResult<Any>(false, error = com.fsaint.androidagent.model.ToolError.FAILED, recoverable = true)
                        ToolEffectReservation.Reserved, null -> {
                            // A capability must not be able to abort the whole conversation.
                            // Return failures to the model so it can explain or recover.
                            val executed = runCatching {
                                if (executionCall.name in definitions) remote.execute(executionCall)
                                else tools.execute(request.session, executionCall)
                            }
                                .getOrElse {
                                    if (it is CancellationException) {
                                        withContext(NonCancellable) { request.onToolResult?.invoke(turn, executionCall,
                                            ToolResult(false, payload = "Interrupted while executing; outcome unknown. Do not automatically retry.", error = com.fsaint.androidagent.model.ToolError.FAILED)) }
                                        throw it
                                    }
                                    ToolResult(false, error = com.fsaint.androidagent.model.ToolError.FAILED, recoverable = true)
                                }
                            withContext(NonCancellable) { toolEffects?.completeToolEffect(
                                request.event.id,
                                executionCall,
                                turn,
                                executed,
                            ) }
                            executed
                        }
                    }
                    withContext(NonCancellable) { request.onToolResult?.invoke(turn, executionCall, result) }
                    currentCoroutineContext().ensureActive()
                    transcript = transcript.copy(
                        turns = transcript.turns + ConversationTurn.AssistantTool(response.call) + ConversationTurn.ToolOutput(response.call, result),
                        nextTurn = transcript.nextTurn + 1,
                    )
                }
            }
        }
        return ConversationResult(
            "I couldn't complete the request within the tool-call limit. The last tool result may indicate what needs attention.",
            transcript,
            transcript.nextTurn,
            calls,
            ConversationStopReason.TURN_LIMIT,
        )
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
