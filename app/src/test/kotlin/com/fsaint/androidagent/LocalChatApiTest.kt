package com.fsaint.androidagent

import com.fsaint.androidagent.model.*
import com.fsaint.androidagent.runtime.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.*

class LocalChatApiTest {
    @Test fun apiReturnsActualToolOutcomesEvenWhenModelClaimsSuccess() = runBlocking {
        val call = ToolCall("mcp_remote_alias", mapOf("secret" to "DO_NOT_EXPOSE"))
        val result = ConversationResult("Everything worked!", ConversationTranscript(listOf(
            ConversationTurn.AssistantTool(call),
            ConversationTurn.ToolOutput(call, ToolResult(false, payload = "PRIVATE_PAYLOAD", error = ToolError.NETWORK_ERROR)),
        )), 1, listOf(call), ConversationStopReason.FINAL_RESPONSE)
        val port = ServerSocket(0).use { it.localPort }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val api = LocalChatApi(scope, port, java.net.InetAddress.getLoopbackAddress()) { input ->
            assertEquals("List MCP servers — please", input)
            LocalChatReply.from(result)
        }
        try {
            api.start()
            val response = withContext(Dispatchers.IO) {
                Socket("127.0.0.1", port).use { socket ->
                    socket.soTimeout = 5_000
                    val body = "List MCP servers — please".toByteArray()
                    socket.getOutputStream().write("POST /chat HTTP/1.1\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray())
                    // Byte-at-a-time delivery also catches partial read and UTF-8 length errors.
                    body.forEach { socket.getOutputStream().write(it.toInt()) }
                    socket.getOutputStream().flush()
                    socket.getInputStream().bufferedReader().readText()
                }
            }
            assertTrue(response.startsWith("HTTP/1.1 200"), response)
            val payload = Json.parseToJsonElement(response.substringAfter("\r\n\r\n")).jsonObject
            assertEquals("Everything worked!", payload.getValue("reply").jsonPrimitive.content)
            val evidence = payload.getValue("toolCalls").jsonArray.single().jsonObject
            assertEquals("mcp_remote_alias", evidence.getValue("tool").jsonPrimitive.content)
            assertFalse(evidence.getValue("success").jsonPrimitive.boolean)
            assertEquals("NETWORK_ERROR", evidence.getValue("error").jsonPrimitive.content)
            assertFalse(response.contains("DO_NOT_EXPOSE"))
            assertFalse(response.contains("PRIVATE_PAYLOAD"))
        } finally { api.stop(); scope.cancel() }
    }
}
