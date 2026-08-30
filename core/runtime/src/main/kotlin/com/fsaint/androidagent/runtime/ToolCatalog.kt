package com.fsaint.androidagent.runtime

import com.fsaint.androidagent.model.ToolCall
import com.fsaint.androidagent.model.ToolError
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
    suspend fun execute(scope: ScopeSnapshot, call: ValidatedToolCall): ToolResult<Any>
}

/** Only [ToolCatalog] can create this value, enforcing validation before execution. */
class ValidatedToolCall private constructor(
    val call: ToolCall,
    val definition: ToolDefinition,
) {
    internal companion object { fun create(call: ToolCall, definition: ToolDefinition) = ValidatedToolCall(call, definition) }
}

/** Scope-filtered catalog validation kept below the model boundary. */
class ToolCatalog(private val provider: ToolProvider) {
    suspend fun validate(scope: ScopeSnapshot, call: ToolCall): ToolValidation {
        if (call.name.isBlank()) return ToolValidation(null, ToolError.NOT_FOUND)
        val definition = provider.discover(scope).firstOrNull { it.id == call.name }
            ?: return ToolValidation(null, ToolError.NOT_FOUND)
        if (definition.requiredResource != null && definition.requiredResource !in scope.resources) {
            return ToolValidation(null, ToolError.SCOPE_DENIED)
        }
        return ToolValidation(definition, validated = ValidatedToolCall.create(call, definition))
    }
}

data class ToolValidation(
    val definition: ToolDefinition?,
    val error: ToolError? = null,
    val validated: ValidatedToolCall? = null,
) {
    val valid: Boolean get() = validated != null && error == null
}
