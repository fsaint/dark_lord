package com.fsaint.androidagent

import com.fsaint.androidagent.runtime.*
import kotlinx.serialization.json.*

/** Diagnostics come from executed tool outputs, never from the model's final prose. */
data class LocalChatReply(val reply: String, val stopReason: String, val toolCalls: JsonArray, val mcpInventory: JsonObject? = null) {
    fun toJson(): String = buildJsonObject {
        put("reply", reply); put("stopReason", stopReason); put("toolCalls", toolCalls)
        mcpInventory?.let { put("mcpInventory", it) }
    }.toString()

    companion object {
        fun from(result: ConversationResult): LocalChatReply {
            val outputs = result.transcript.turns.filterIsInstance<ConversationTurn.ToolOutput>()
            return LocalChatReply(result.response ?: "The agent did not produce a final response", result.stopReason.name,
                buildJsonArray { outputs.forEach { output -> add(buildJsonObject {
                    put("tool", output.call.name); put("success", output.result.success)
                    put("error", output.result.error?.name); put("verification", output.result.verification.name)
                    // Do not expose arguments, remote result bodies, credentials or account data.
                }) } },
                outputs.lastOrNull { it.call.name == MCP_INVENTORY_TOOL && it.result.success }?.result?.payload?.let {
                    runCatching { Json.parseToJsonElement(it.toString()).jsonObject }.getOrNull()
                })
        }
    }
}
