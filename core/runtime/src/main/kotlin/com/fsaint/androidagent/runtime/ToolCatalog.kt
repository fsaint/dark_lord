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
    suspend fun execute(call: ValidatedToolCall): ToolResult<Any>
}

sealed interface ValidatedToolCall {
    val call: ToolCall
    val definition: ToolDefinition
}

/** Scope-filtered catalog validation kept below the model boundary. */
class ToolCatalog(private val provider: ToolProvider) {
    private data class IssuedCall(override val call: ToolCall, override val definition: ToolDefinition, val catalog: ToolCatalog, val scope: ScopeSnapshot) : ValidatedToolCall

    suspend fun validate(scope: ScopeSnapshot, call: ToolCall): ToolValidation {
        if (call.name.isBlank()) return ToolValidation(null, ToolError.NOT_FOUND)
        val definition = provider.discover(scope).firstOrNull { it.id == call.name }
            ?: return ToolValidation(null, ToolError.NOT_FOUND)
        if (definition.requiredResource != null && definition.requiredResource !in scope.resources) {
            return ToolValidation(null, ToolError.SCOPE_DENIED)
        }
        return ToolValidation(definition, validated = IssuedCall(call, definition, this, scope))
    }

    suspend fun execute(scope: ScopeSnapshot, call: ValidatedToolCall): ToolResult<Any> {
        val issued = call as? IssuedCall
            ?: return ToolResult(false, error = ToolError.SCOPE_DENIED)
        if (issued.catalog !== this || issued.scope !== scope) return ToolResult(false, error = ToolError.SCOPE_DENIED)
        return provider.execute(issued)
    }
}

data class ToolValidation(
    val definition: ToolDefinition?,
    val error: ToolError? = null,
    val validated: ValidatedToolCall? = null,
) {
    val valid: Boolean get() = validated != null && error == null
}
