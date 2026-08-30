package com.fsaint.androidagent.capabilities.accessibility

import com.fsaint.androidagent.model.AgentCapability
import com.fsaint.androidagent.model.AgentEvent
import com.fsaint.androidagent.model.AgentTool
import com.fsaint.androidagent.model.CapabilityStatus
import com.fsaint.androidagent.model.ToolCall
import com.fsaint.androidagent.model.ToolError
import com.fsaint.androidagent.model.ToolResult
import com.fsaint.androidagent.model.VerificationState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

data class AccessibilityTool(override val id: String) : AgentTool

data class AccessibilityServiceState(
    val enabled: Boolean,
    val connected: Boolean,
)

data class AccessibilityTarget(
    val packageName: String,
    val viewId: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
) {
    fun isExplicitlyAddressed(): Boolean = packageName.isNotBlank() &&
        listOf(viewId, text, contentDescription).any { !it.isNullOrBlank() }
}

data class AccessibilityNodeSnapshot(
    val packageName: String,
    val className: String,
    val viewId: String?,
    val text: String?,
    val contentDescription: String?,
    val clickable: Boolean,
    val enabled: Boolean,
)

enum class AccessibilityAction {
    CLICK,
    FOCUS,
    SET_TEXT,
}

data class AccessibilityActionRequest(
    val target: AccessibilityTarget,
    val action: AccessibilityAction,
    val value: String? = null,
)

data class AccessibilityActionResult(
    val packageName: String,
    val action: AccessibilityAction,
)

sealed interface AccessibilityInspectOutcome {
    data class Success(val node: AccessibilityNodeSnapshot) : AccessibilityInspectOutcome
    data object PermissionRequired : AccessibilityInspectOutcome
    data object ServiceUnavailable : AccessibilityInspectOutcome
    data object NotFound : AccessibilityInspectOutcome
    data object Ambiguous : AccessibilityInspectOutcome
    data object Unsupported : AccessibilityInspectOutcome
}

enum class AccessibilityActionOutcome {
    Performed,
    PermissionRequired,
    ServiceUnavailable,
    NotFound,
    Ambiguous,
    Rejected,
    Unsupported,
}

interface AccessibilityAdapter {
    fun status(): AccessibilityServiceState
    suspend fun inspect(target: AccessibilityTarget): AccessibilityInspectOutcome
    suspend fun perform(request: AccessibilityActionRequest): AccessibilityActionOutcome
}

class AccessibilityCapability(private val adapter: AccessibilityAdapter) : AgentCapability {
    override val id = "accessibility"
    override val version = "1.0"

    override suspend fun initialize(): CapabilityStatus = status()

    override fun tools(): List<AgentTool> = listOf(
        AccessibilityTool("accessibility.status"),
        AccessibilityTool("accessibility.inspect"),
        AccessibilityTool("accessibility.action"),
    )

    override fun events(): Flow<AgentEvent> = emptyFlow()

    override fun status(): CapabilityStatus = adapter.status().let { state ->
        CapabilityStatus(
            available = state.enabled && state.connected,
            details = mapOf(
                "enabled" to state.enabled.toString(),
                "connected" to state.connected.toString(),
            ),
        )
    }

    suspend fun readStatus(): ToolResult<AccessibilityServiceState> = ToolResult(
        success = true,
        payload = adapter.status(),
        verification = VerificationState.VERIFIED,
    )

    suspend fun inspect(target: AccessibilityTarget): ToolResult<AccessibilityNodeSnapshot> {
        if (!target.isExplicitlyAddressed()) return ToolResult(success = false, error = ToolError.SCOPE_DENIED)

        return when (val outcome = adapter.inspect(target)) {
            is AccessibilityInspectOutcome.Success -> ToolResult(
                success = true,
                payload = outcome.node,
                verification = VerificationState.VERIFIED,
            )
            AccessibilityInspectOutcome.PermissionRequired -> permissionRequired()
            AccessibilityInspectOutcome.ServiceUnavailable -> serviceUnavailable()
            AccessibilityInspectOutcome.NotFound -> ToolResult(
                success = false,
                error = ToolError.NOT_FOUND,
                recoverable = true,
            )
            AccessibilityInspectOutcome.Ambiguous -> ToolResult(success = false, error = ToolError.SCOPE_DENIED)
            AccessibilityInspectOutcome.Unsupported -> ToolResult(success = false, error = ToolError.UNSUPPORTED)
        }
    }

    suspend fun perform(request: AccessibilityActionRequest): ToolResult<AccessibilityActionResult> {
        if (!request.target.isExplicitlyAddressed()) {
            return ToolResult(success = false, error = ToolError.SCOPE_DENIED)
        }
        if (request.action == AccessibilityAction.SET_TEXT && request.value == null) {
            return ToolResult(success = false, error = ToolError.SCOPE_DENIED)
        }

        return when (adapter.perform(request)) {
            AccessibilityActionOutcome.Performed -> ToolResult(
                success = true,
                payload = AccessibilityActionResult(request.target.packageName, request.action),
                verification = VerificationState.VERIFIED,
            )
            AccessibilityActionOutcome.PermissionRequired -> permissionRequired()
            AccessibilityActionOutcome.ServiceUnavailable -> serviceUnavailable()
            AccessibilityActionOutcome.NotFound -> ToolResult(
                success = false,
                error = ToolError.NOT_FOUND,
                recoverable = true,
            )
            AccessibilityActionOutcome.Ambiguous -> ToolResult(success = false, error = ToolError.SCOPE_DENIED)
            AccessibilityActionOutcome.Rejected -> ToolResult(
                success = false,
                error = ToolError.OS_RESTRICTED,
                recoverable = true,
            )
            AccessibilityActionOutcome.Unsupported -> ToolResult(success = false, error = ToolError.UNSUPPORTED)
        }
    }

    fun toolHandlers(): Map<String, suspend (ToolCall) -> ToolResult<Any>> = mapOf(
        "accessibility.status" to { _ -> readStatus().toAnyResult() },
        "accessibility.inspect" to { call -> inspect(call.toTarget()).toAnyResult() },
        "accessibility.action" to { call -> perform(call).toAnyResult() },
    )

    private suspend fun perform(call: ToolCall): ToolResult<AccessibilityActionResult> {
        val action = call.arguments["action"]?.let { value ->
            AccessibilityAction.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
        } ?: return ToolResult(success = false, error = ToolError.UNSUPPORTED)
        return perform(
            AccessibilityActionRequest(
                target = call.toTarget(),
                action = action,
                value = call.arguments["value"],
            ),
        )
    }
}

private fun ToolCall.toTarget() = AccessibilityTarget(
    packageName = arguments["packageName"].orEmpty(),
    viewId = arguments["viewId"].nonBlankOrNull(),
    text = arguments["text"].nonBlankOrNull(),
    contentDescription = arguments["contentDescription"].nonBlankOrNull(),
)

private fun String?.nonBlankOrNull(): String? = this?.takeIf(String::isNotBlank)

private fun <T> permissionRequired(): ToolResult<T> = ToolResult(
    success = false,
    error = ToolError.PERMISSION_REQUIRED,
    recoverable = true,
)

private fun <T> serviceUnavailable(): ToolResult<T> = ToolResult(
    success = false,
    error = ToolError.APP_NOT_RUNNING,
    recoverable = true,
)

private fun <T> ToolResult<T>.toAnyResult(): ToolResult<Any> = ToolResult(
    success = success,
    payload = payload as Any?,
    error = error,
    recoverable = recoverable,
    verification = verification,
)
