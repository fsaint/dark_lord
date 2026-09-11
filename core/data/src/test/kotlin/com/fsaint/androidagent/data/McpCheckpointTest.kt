package com.fsaint.androidagent.data

import androidx.test.core.app.ApplicationProvider
import com.fsaint.androidagent.model.*
import com.fsaint.androidagent.runtime.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
class McpCheckpointTest {
    @Test fun legacyCheckpointRemainsReadable() = runTest {
        val db = AgentDatabaseTestFactory.inMemory(ApplicationProvider.getApplicationContext())
        try {
            val repository = DurableStateRepository(db.durableStateDao())
            fun encoded(value: String) = android.util.Base64.encodeToString(value.toByteArray(), android.util.Base64.NO_WRAP)
            repository.save(ConversationMessageEntity("legacy:0", "legacy", 0, "A|${encoded("device.battery")}|${encoded("label=old")}".toByteArray()))
            repository.save(ConversationMessageEntity("legacy:1", "legacy", 1, "F|${encoded("Done")}".toByteArray()))
            val restored = RoomConversationCheckpointStore(repository).load("legacy")!!
            assertEquals(ToolCall("device.battery", mapOf("label" to "old")), (restored.turns[0] as ConversationTurn.AssistantTool).call)
            assertEquals("Done", (restored.turns[1] as ConversationTurn.AssistantFinal).text)
        } finally { db.close() }
    }
    @Test fun checkpointKeepsNestedJsonAndFailureOutcome() = runTest {
        val db = AgentDatabaseTestFactory.inMemory(ApplicationProvider.getApplicationContext())
        try {
            val store = RoomConversationCheckpointStore(DurableStateRepository(db.durableStateDao()))
            val call = ToolCall("mcp_server_tool", mapOf("_mcp_arguments_json" to """{"items":[null,true,3,{"q":"a&b=c"}]}"""))
            val result = ToolResult<Any>(false, payload = "failure", error = ToolError.NETWORK_ERROR, recoverable = false)
            store.save("fixture", ConversationTranscript(listOf(ConversationTurn.AssistantTool(call), ConversationTurn.ToolOutput(call, result)), 1))
            val restored = store.load("fixture")!!
            assertEquals(1, restored.nextTurn)
            assertEquals(call, (restored.turns[0] as ConversationTurn.AssistantTool).call)
            val output = restored.turns[1] as ConversationTurn.ToolOutput
            assertEquals(call, output.call)
            assertFalse(output.result.success)
            assertEquals(ToolError.NETWORK_ERROR, output.result.error)
        } finally { db.close() }
    }
}
