package com.fsaint.androidagent.model

import kotlinx.coroutines.flow.Flow

data class AgentEvent(
    val id: String,
    val type: String,
    val source: String,
    val occurredAtEpochMs: Long,
    val payload: Map<String, String> = emptyMap(),
)

enum class DeliveryState { PENDING, IN_PROGRESS, COMPLETED }
enum class AuthorizationDecision { ALLOW, DENY }
enum class VerificationState { UNVERIFIED, VERIFIED, FAILED }
enum class PrincipalRole { OWNER, KNOWN, UNKNOWN }
enum class EscalationStatus { OPEN, RESOLVED, CANCELLED }

data class ScopeGrant(
    val id: String,
    val principalId: String,
    val resourceType: String,
    val resourceId: String,
    val granted: Boolean,
)

data class ScopedAgentSession(
    val id: String,
    val principalId: String,
    val role: PrincipalRole,
    val scopeId: String,
    val channel: String,
    val memoryNamespace: String,
    val createdAtEpochMs: Long,
)

data class AuditRecord(
    val id: String,
    val occurredAtEpochMs: Long,
    val eventId: String? = null,
    val principalId: String? = null,
    val scopeId: String? = null,
    val sessionId: String? = null,
    val skillId: String? = null,
    val tool: String,
    val authorization: AuthorizationDecision,
    val verification: VerificationState,
    val result: String? = null,
)

enum class ToolError {
    UNSUPPORTED, NOT_FOUND, PERMISSION_REQUIRED, SCOPE_DENIED, USER_CONFIRMATION_REQUIRED,
    DEVICE_BUSY, TIMEOUT, NETWORK_ERROR, APP_NOT_RUNNING, SECURE_WINDOW, OS_RESTRICTED,
    CANCELLED, FAILED,
}

data class ToolResult<T>(
    val success: Boolean,
    val payload: T? = null,
    val error: ToolError? = null,
    val recoverable: Boolean = false,
    val verification: VerificationState = VerificationState.UNVERIFIED,
)

data class ToolCall(val name: String, val arguments: Map<String, String> = emptyMap())

data class CapabilityStatus(val available: Boolean, val details: Map<String, String> = emptyMap())
interface AgentTool { val id: String }
interface AgentCapability {
    val id: String
    val version: String
    suspend fun initialize(): CapabilityStatus
    fun tools(): List<AgentTool>
    fun events(): Flow<AgentEvent>
    fun status(): CapabilityStatus
}
