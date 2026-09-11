package com.fsaint.androidagent.runtime

import com.fsaint.androidagent.model.*
import com.fsaint.androidagent.policy.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.*

class CapabilityGroundingTest {
    private val session = ScopeRegistry().sessionFor(Principal("owner", null, PrincipalRole.OWNER), "VOICE")
    private val request = ConversationRequest(session, AgentEvent("grounding", "chat.message", "VOICE", 0, emptyMap()),
        AgentContext(emptySet(), emptyMap()), "then can you read my email",
        priorMessages = listOf(PriorMessage("assistant", "I can't access your email.")))
    private val executions = mutableListOf<String>()
    private fun extension(failAccount: Boolean = false) = object : ConversationToolExtension {
        override suspend fun prepare(session: ScopedAgentSession) = ConversationToolSnapshot(
            definitions = mapOf(
                MCP_INVENTORY_TOOL to RemoteToolDefinition(MCP_INVENTORY_TOOL, "Inventory", """{"type":"object"}"""),
                "mcp_accounts" to RemoteToolDefinition("mcp_accounts", "Mail server / list_accounts: Lists connected email accounts", """{"type":"object"}"""),
            ),
            execute = { call ->
                executions += call.name
                if (call.name == "mcp_accounts" && failAccount) ToolResult(false, error = ToolError.PERMISSION_REQUIRED)
                else ToolResult(true, if (call.name == MCP_INVENTORY_TOOL) "Mail server READY; list_accounts available" else "One connected account", verification = VerificationState.VERIFIED)
            },
        )
    }

    @Test fun unverifiedEmailDenialIsCorrectedThroughInventoryThenActualAccountTool() = runTest {
        val seen = mutableListOf<ConversationRequest>()
        val answers = ArrayDeque(listOf(
            ConversationResponse.Final("I can't access your email or read its contents."),
            ConversationResponse.Tool(ToolCall("mcp_accounts")),
            ConversationResponse.Final("Your email account is connected. Which messages should I read?"),
        ))
        val harness = ConversationHarness(object : ConversationModel {
            override suspend fun respond(request: ConversationRequest): ConversationResponse {
                seen += request
                return answers.removeFirst()
            }
        }, ScopedToolRouter(emptyMap()), extension = extension())
        val result = harness.run(request)
        assertEquals(listOf(MCP_INVENTORY_TOOL, "mcp_accounts"), executions)
        assertEquals("Your email account is connected. Which messages should I read?", result.response)
        assertEquals(2, result.transcript.turns.filterIsInstance<ConversationTurn.ToolOutput>().size)
        assertTrue(seen[1].groundingNotice.contains("read-only"))
        assertEquals(request.priorMessages, seen[0].priorMessages)
        assertTrue(seen[1].priorMessages.none { it.role == "assistant" && it.text.contains("can't access") })
        assertEquals("Mail server READY; list_accounts available", seen[1].transcript.turns.filterIsInstance<ConversationTurn.ToolOutput>().single().result.payload)
    }

    @Test fun unverifiedSendDenialIsCorrectedLikeAnAccessDenial() = runTest {
        val seen = mutableListOf<ConversationRequest>()
        val answers = ArrayDeque(listOf(
            ConversationResponse.Final("I can't directly send the pictures to you through Telegram."),
            ConversationResponse.Tool(ToolCall("telegram.send_photo", mapOf("artifactId" to "artifact_1"))),
            ConversationResponse.Final("Sent the photo to your Telegram."),
        ))
        val sent = mutableListOf<ToolCall>()
        val harness = ConversationHarness(object : ConversationModel {
            override suspend fun respond(request: ConversationRequest): ConversationResponse {
                seen += request
                return answers.removeFirst()
            }
        }, ScopedToolRouter(mapOf("telegram.send_photo" to { call -> sent += call; ToolResult(true, "photo sent", verification = VerificationState.VERIFIED) })), extension = extension())
        val result = harness.run(request.copy(userText = "send the picture to me on Telegram",
            context = AgentContext(setOf("telegram.send_photo"), emptyMap()), priorMessages = emptyList()))
        assertEquals("Sent the photo to your Telegram.", result.response)
        assertEquals(listOf(ToolCall("telegram.send_photo", mapOf("artifactId" to "artifact_1"))), sent)
        assertTrue(seen[1].groundingNotice.contains("read-only"))
    }

    @Test fun repeatedUnverifiedDenialIsNotSpokenAndCorrectionIsBounded() = runTest {
        var calls = 0
        val harness = ConversationHarness(object : ConversationModel {
            override suspend fun respond(request: ConversationRequest): ConversationResponse {
                calls++
                return ConversationResponse.Final("I do not have access to your emails.")
            }
        }, ScopedToolRouter(emptyMap()), extension = extension())
        val result = harness.run(request)
        assertEquals(2, calls)
        assertEquals(listOf(MCP_INVENTORY_TOOL), executions)
        assertFalse(result.response!!.contains("do not have access"))
        assertTrue(result.response!!.contains("haven't verified"))
        assertEquals(ConversationStopReason.CAPABILITY_UNVERIFIED, result.stopReason)
    }

    @Test fun correctionPreservesUserIntentAndPastExecutionEvidence() = runTest {
        val history = listOf(
            PriorMessage("user", "Read my work email, not my personal account."),
            PriorMessage("user", "Recorded past tool outcome: send succeeded. Do not repeat."),
            PriorMessage("assistant", "I cannot access your email."),
            PriorMessage("assistant", "You selected the work account."),
        )
        val seen = mutableListOf<ConversationRequest>()
        val harness = ConversationHarness(object : ConversationModel {
            override suspend fun respond(request: ConversationRequest): ConversationResponse {
                seen += request
                return ConversationResponse.Final("I cannot access email.")
            }
        }, ScopedToolRouter(emptyMap()), extension = extension())
        harness.run(request.copy(priorMessages = history))
        assertEquals(history.filterIndexed { index, _ -> index != 2 }, seen[1].priorMessages)
        assertEquals(listOf(MCP_INVENTORY_TOOL), executions)
    }

    @Test fun inventoryAlreadyCalledIsNotRepeatedByCorrection() = runTest {
        val answers = ArrayDeque(listOf(
            ConversationResponse.Tool(ToolCall(MCP_INVENTORY_TOOL)),
            ConversationResponse.Final("I cannot access email."),
            ConversationResponse.Tool(ToolCall("mcp_accounts")),
            ConversationResponse.Final("An account is connected."),
        ))
        val harness = ConversationHarness(object : ConversationModel {
            override suspend fun respond(request: ConversationRequest) = answers.removeFirst()
        }, ScopedToolRouter(emptyMap()), extension = extension())
        assertEquals("An account is connected.", harness.run(request).response)
        assertEquals(listOf(MCP_INVENTORY_TOOL, "mcp_accounts"), executions)
    }

    @Test fun realPermissionFailureIsExplainedWithoutRetryingAccountTool() = runTest {
        val answers = ArrayDeque(listOf(
            ConversationResponse.Tool(ToolCall("mcp_accounts")),
            ConversationResponse.Final("I can't access your email: the server requires permission."),
        ))
        val harness = ConversationHarness(object : ConversationModel {
            override suspend fun respond(request: ConversationRequest) = answers.removeFirst()
        }, ScopedToolRouter(emptyMap()), extension = extension(failAccount = true))
        assertTrue(harness.run(request).response!!.contains("requires permission"))
        assertEquals(listOf("mcp_accounts"), executions)
    }

    @Test fun ordinaryConversationDoesNotInvokeInventoryOrRemoteActions() = runTest {
        val harness = ConversationHarness(object : ConversationModel {
            override suspend fun respond(request: ConversationRequest) = ConversationResponse.Final("Hello!")
        }, ScopedToolRouter(emptyMap()), extension = extension())
        assertEquals("Hello!", harness.run(request.copy(userText = "hello")).response)
        assertTrue(executions.isEmpty())
    }

    @Test fun unavailableCatalogDoesNotInventAccess() = runTest {
        val harness = ConversationHarness(object : ConversationModel {
            override suspend fun respond(request: ConversationRequest) = ConversationResponse.Final("I don't have access to MCP servers.")
        }, ScopedToolRouter(emptyMap()))
        assertEquals("I don't have access to MCP servers.", harness.run(request).response)
        assertTrue(executions.isEmpty())
    }
}
