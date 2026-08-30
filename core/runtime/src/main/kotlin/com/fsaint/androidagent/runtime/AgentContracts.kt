package com.fsaint.androidagent.runtime

import com.fsaint.androidagent.model.AgentEvent
import com.fsaint.androidagent.model.ScopedAgentSession
import com.fsaint.androidagent.model.ToolCall

data class AgentRequest(
    val runId: String,
    val session: ScopedAgentSession,
    val event: AgentEvent,
    val userText: String,
) { init { require(runId.isNotBlank()) } }

data class ModelRequest(
    val request: AgentRequest,
    val transcript: ConversationTranscript = ConversationTranscript(),
    val memory: Map<String, List<String>> = emptyMap(),
    val skills: List<SkillDefinition> = emptyList(),
    val tools: List<ToolDefinition> = emptyList(),
)

sealed interface ModelResponse {
    data class ToolCalls(val calls: List<ToolCall>) : ModelResponse
    data class Final(val text: String) : ModelResponse
    data class Escalate(val question: String, val reason: String) : ModelResponse
}

data class ScopeSnapshot(
    val session: ScopedAgentSession,
    val resources: Set<String> = emptySet(),
    val id: String = session.scopeId,
) { init { require(id.isNotBlank()) } }

data class SkillDefinition(
    val id: String,
    val version: String = "1",
    val instructions: String = "",
    val examples: List<String> = emptyList(),
    val references: List<String> = emptyList(),
) { init { require(id.isNotBlank()); require(version.isNotBlank()) } }

enum class Confirmation { NONE, USER_CONFIRMATION_REQUIRED, OWNER_APPROVAL_REQUIRED }

enum class ToolErrorCode {
    INVALID_TOOL_ID, INVALID_ARGUMENTS, SCOPE_DENIED, CONFIRMATION_REQUIRED,
    TIMEOUT, CANCELLED, FAILED, NOT_FOUND,
}

enum class AgentRunState { FINAL, TOOL_CALL, ESCALATE, CANCELLED, TURN_LIMIT, FAILED }

data class AgentRunResult(
    val runId: String,
    val state: AgentRunState,
    val response: String? = null,
    val error: ToolErrorCode? = null,
) { init { require(runId.isNotBlank()) } }

interface AgentHarness {
    suspend fun run(request: AgentRequest): AgentRunResult
    suspend fun resume(runId: String): AgentRunResult
    suspend fun cancel(runId: String)

    companion object {
        const val MAX_TURNS = 8
        const val MAX_PARALLEL_TOOL_CALLS = 4
    }
}
