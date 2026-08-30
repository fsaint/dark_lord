package com.fsaint.androidagent.runtime

import com.fsaint.androidagent.model.ToolCall
import com.fsaint.androidagent.model.ToolResult

data class ToolDefinition(
    val id: String,
    val description: String,
    val argumentSchema: String,
    val source: String,
    val requiredResource: String?,
    val confirmation: Confirmation,
    val timeoutMillis: Long,
    val concurrencyKey: String,
) {
    init {
        require(id.isNotBlank())
        require(source.isNotBlank())
        require(timeoutMillis > 0)
    }
}

interface ToolProvider {
    suspend fun discover(scope: ScopeSnapshot): List<ToolDefinition>
    suspend fun execute(scope: ScopeSnapshot, call: ToolCall): ToolResult<Any>
}

/** Scope-filtered catalog validation kept below the model boundary. */
class ToolCatalog(private val provider: ToolProvider) {
    suspend fun validate(scope: ScopeSnapshot, call: ToolCall): ToolValidation {
        if (call.name.isBlank()) return ToolValidation(null, ToolErrorCode.INVALID_TOOL_ID)
        val definition = provider.discover(scope).firstOrNull { it.id == call.name }
            ?: return ToolValidation(null, ToolErrorCode.INVALID_TOOL_ID)
        if (definition.requiredResource != null && definition.requiredResource !in scope.resources) {
            return ToolValidation(null, ToolErrorCode.SCOPE_DENIED)
        }
        return ToolValidation(definition)
    }
}

data class ToolValidation(val definition: ToolDefinition?, val error: ToolErrorCode? = null) {
    val valid: Boolean get() = definition != null && error == null
}
