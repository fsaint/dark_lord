package com.fsaint.androidagent.capabilities.apps

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

data class AppsTool(override val id: String) : AgentTool

data class InstalledApp(
    val label: String,
    val packageName: String,
    val enabled: Boolean,
)

data class AppLaunchResult(val packageName: String)

sealed interface AppsListOutcome {
    data class Success(val apps: List<InstalledApp>) : AppsListOutcome
    data object PermissionRequired : AppsListOutcome
    data object Unsupported : AppsListOutcome
}

enum class AppLaunchOutcome {
    Launched,
    NotFound,
    NotLaunchable,
    Failed,
    PermissionRequired,
    Unsupported,
}

interface AppsAdapter {
    suspend fun list(): AppsListOutcome
    suspend fun launch(packageName: String): AppLaunchOutcome
}

class AppsCapability(private val adapter: AppsAdapter) : AgentCapability {
    override val id = "apps"
    override val version = "1.0"
    private val current = CapabilityStatus(available = true)

    override suspend fun initialize(): CapabilityStatus = current

    override fun tools(): List<AgentTool> = listOf(
        AppsTool("apps.list"),
        AppsTool("apps.launch"),
    )

    override fun events(): Flow<AgentEvent> = emptyFlow()

    override fun status(): CapabilityStatus = current

    suspend fun list(): ToolResult<List<InstalledApp>> = when (val outcome = adapter.list()) {
        is AppsListOutcome.Success -> ToolResult(
            success = true,
            payload = outcome.apps,
            verification = VerificationState.VERIFIED,
        )
        AppsListOutcome.PermissionRequired -> ToolResult(
            success = false,
            error = ToolError.PERMISSION_REQUIRED,
            recoverable = true,
        )
        AppsListOutcome.Unsupported -> ToolResult(
            success = false,
            error = ToolError.UNSUPPORTED,
        )
    }

    suspend fun launch(packageName: String): ToolResult<AppLaunchResult> = when (adapter.launch(packageName)) {
        AppLaunchOutcome.Launched -> ToolResult(
            success = true,
            payload = AppLaunchResult(packageName),
            verification = VerificationState.VERIFIED,
        )
        AppLaunchOutcome.NotFound -> ToolResult(success = false, error = ToolError.NOT_FOUND)
        AppLaunchOutcome.NotLaunchable,
        AppLaunchOutcome.Failed,
        -> ToolResult(success = false, error = ToolError.APP_NOT_RUNNING, recoverable = true)
        AppLaunchOutcome.PermissionRequired -> ToolResult(
            success = false,
            error = ToolError.PERMISSION_REQUIRED,
            recoverable = true,
        )
        AppLaunchOutcome.Unsupported -> ToolResult(success = false, error = ToolError.UNSUPPORTED)
    }

    fun toolHandlers(): Map<String, suspend (ToolCall) -> ToolResult<Any>> = mapOf(
        "apps.list" to { _ -> list().toAnyResult() },
        "apps.launch" to { call -> launch(call.arguments["packageName"].orEmpty()).toAnyResult() },
    )
}

private fun <T> ToolResult<T>.toAnyResult(): ToolResult<Any> = ToolResult(
    success = success,
    payload = payload as Any?,
    error = error,
    recoverable = recoverable,
    verification = verification,
)
