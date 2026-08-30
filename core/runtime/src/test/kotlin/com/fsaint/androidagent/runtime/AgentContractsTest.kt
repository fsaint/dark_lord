package com.fsaint.androidagent.runtime

import com.fsaint.androidagent.model.AgentEvent
import com.fsaint.androidagent.model.PrincipalRole
import com.fsaint.androidagent.model.ScopedAgentSession
import com.fsaint.androidagent.model.ToolCall
import com.fsaint.androidagent.model.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentContractsTest {
    private val session = ScopedAgentSession("s", "p", PrincipalRole.OWNER, "owner", "sms", "p", 0)
    private val request = AgentRequest("run-1", session, AgentEvent("e", "message", "sms", 0), "hello")

    @Test fun `responses represent final tool calls and escalation`() {
        assertEquals("done", ModelResponse.Final("done").text)
        assertEquals(listOf(ToolCall("device.battery")), ModelResponse.ToolCalls(listOf(ToolCall("device.battery"))).calls)
        assertEquals("why", ModelResponse.Escalate("why", "owner approval").question)
    }

    @Test fun `tool definitions reject unsafe identity and timeout`() {
        assertFailsWith<IllegalArgumentException> { ToolDefinition("", "desc", "{}", "android", "device", Confirmation.NONE, 1, "device") }
        assertFailsWith<IllegalArgumentException> { ToolDefinition("tool", "desc", "{}", "android", "device", Confirmation.NONE, 0, "device") }
    }

    @Test fun `tool catalog rejects calls not present in discovered definitions`() = runBlocking {
        val provider = object : ToolProvider {
            override suspend fun discover(scope: ScopeSnapshot) = listOf(ToolDefinition("known", "", "{}", "test", null, Confirmation.NONE, 1, ""))
            override suspend fun execute(scope: ScopeSnapshot, call: ToolCall): ToolResult<Any> = ToolResult(true, Unit)
        }
        assertEquals(ToolErrorCode.INVALID_TOOL_ID, ToolCatalog(provider).validate(ScopeSnapshot(session), ToolCall("unknown")).error)
    }

    @Test fun `terminal states have stable serialization`() {
        AgentRunState.entries.forEach { assertEquals(it, AgentRunState.valueOf(it.name)) }
        assertEquals("TURN_LIMIT", AgentRunState.TURN_LIMIT.name)
    }

    @Test fun `safety budgets are eight turns and four calls`() {
        assertEquals(8, AgentHarness.MAX_TURNS)
        assertEquals(4, AgentHarness.MAX_PARALLEL_TOOL_CALLS)
    }
}
