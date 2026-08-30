package com.fsaint.androidagent.runtime

import com.fsaint.androidagent.model.AgentEvent
import com.fsaint.androidagent.model.PrincipalRole
import com.fsaint.androidagent.model.ToolCall
import com.fsaint.androidagent.model.ToolResult
import com.fsaint.androidagent.model.VerificationState
import com.fsaint.androidagent.policy.AgentContext
import com.fsaint.androidagent.policy.Principal
import com.fsaint.androidagent.policy.ScopeRegistry
import com.fsaint.androidagent.policy.ScopedToolRouter
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversationHarnessTest {
    private val event = AgentEvent("e1", "user.message", "+1", 1, mapOf("body" to "hello"))
    private val session = ScopeRegistry().sessionFor(Principal("owner", "+1", PrincipalRole.OWNER), "SMS")
    private val context = AgentContext(emptySet(), emptyMap())

    @Test fun executesToolThenReturnsFinalModelResponse() = runTest {
        val model = ScriptedConversationModel(
            ConversationResponse.Tool(ToolCall("device.battery")),
            ConversationResponse.Final("Battery is 72%"),
        )
        val harness = ConversationHarness(model, router("device.battery" to { ToolResult(true, "72%", verification = VerificationState.VERIFIED) }))

        val result = harness.run(ConversationRequest(session, event, context, "hello"))

        assertEquals("Battery is 72%", result.response)
        assertEquals(2, result.turns)
        assertEquals(listOf("device.battery"), result.toolCalls.map { it.name })
    }

    @Test fun stopsAtEightTurnsAndReportsBudget() = runTest {
        val model = RepeatingToolModel()
        val harness = ConversationHarness(model, router("device.battery" to { ToolResult(true, "72%", verification = VerificationState.VERIFIED) }))

        val result = harness.run(ConversationRequest(session, event, context, "loop"))

        assertEquals(ConversationStopReason.TURN_LIMIT, result.stopReason)
        assertEquals(8, result.turns)
    }

    @Test fun cancelledRunCanResumeFromCheckpoint() = runTest {
        val checkpoints = InMemoryConversationCheckpointStore()
        val model = ScriptedConversationModel(ConversationResponse.Tool(ToolCall("device.battery")), ConversationResponse.Final("done"))
        val harness = ConversationHarness(model, router("device.battery" to { ToolResult(true, "72%", verification = VerificationState.VERIFIED) }), checkpoints)
        harness.cancel("conversation-1", ConversationTranscript(emptyList(), 1))

        val result = harness.resume(ConversationRequest(session, event, context, "hello"), "conversation-1")

        assertEquals("done", result.response)
        assertTrue(checkpoints.load("conversation-1") == null)
    }
}

private fun router(vararg tools: Pair<String, suspend (ToolCall) -> ToolResult<*>>) = ScopedToolRouter(
    tools.associate { (name, handler) -> name to { call -> handler(call).asAny() } },
)

private fun ToolResult<*>.asAny() = ToolResult(success, payload as Any?, error, recoverable, verification)

private class ScriptedConversationModel(private vararg val responses: ConversationResponse) : ConversationModel {
    private var index = 0
    override suspend fun respond(request: ConversationRequest): ConversationResponse = responses[index++]
}

private class RepeatingToolModel : ConversationModel {
    override suspend fun respond(request: ConversationRequest) = ConversationResponse.Tool(ToolCall("device.battery"))
}
