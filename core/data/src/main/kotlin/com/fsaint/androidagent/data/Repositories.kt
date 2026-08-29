package com.fsaint.androidagent.data

import com.fsaint.androidagent.model.AgentEvent
import com.fsaint.androidagent.model.AuditRecord
import com.fsaint.androidagent.model.AuthorizationDecision
import com.fsaint.androidagent.model.DeliveryState
import com.fsaint.androidagent.model.VerificationState
import java.nio.charset.StandardCharsets

class EventRepository(private val dao: EventDao) {
    suspend fun enqueue(event: AgentEvent) {
        dao.insert(
            EventEntity(
                id = event.id,
                type = event.type,
                source = event.source,
                occurredAtEpochMs = event.occurredAtEpochMs,
                payload = EventPayloadCodec.encode(event.payload),
                deliveryState = DeliveryState.PENDING.name,
            ),
        )
    }

    suspend fun nextPending(): AgentEvent? = dao.nextUndelivered()?.let { entity ->
        AgentEvent(entity.id, entity.type, entity.source, entity.occurredAtEpochMs, EventPayloadCodec.decode(entity.payload))
    }

    suspend fun markCompleted(eventId: String) = dao.markCompleted(eventId)
}

class AuditRepository(private val dao: AuditRecordDao) {
    suspend fun append(record: AuditRecord) {
        dao.insert(
            AuditRecordEntity(
                id = record.id, occurredAtEpochMs = record.occurredAtEpochMs, eventId = record.eventId,
                principalId = record.principalId, scopeId = record.scopeId, sessionId = record.sessionId,
                skillId = record.skillId, tool = record.tool, authorization = record.authorization.name,
                verification = record.verification.name, result = record.result?.toByteArray(StandardCharsets.UTF_8),
            ),
        )
    }

    suspend fun list(): List<AuditRecord> = dao.all().map { entity ->
        AuditRecord(
            id = entity.id, occurredAtEpochMs = entity.occurredAtEpochMs, eventId = entity.eventId,
            principalId = entity.principalId, scopeId = entity.scopeId, sessionId = entity.sessionId,
            skillId = entity.skillId, tool = entity.tool,
            authorization = AuthorizationDecision.valueOf(entity.authorization),
            verification = VerificationState.valueOf(entity.verification),
            result = entity.result?.toString(StandardCharsets.UTF_8),
        )
    }
}

class DurableStateRepository(private val dao: DurableStateDao) {
    suspend fun save(value: PrincipalEntity) = dao.putPrincipal(value)
    suspend fun save(value: ScopeGrantEntity) = dao.putScopeGrant(value)
    suspend fun save(value: SessionEntity) = dao.putSession(value)
    suspend fun save(value: ConversationMessageEntity) = dao.putConversation(value)
    suspend fun save(value: MemoryEntryEntity) = dao.putMemory(value)
    suspend fun save(value: ScheduleEntity) = dao.putSchedule(value)
    suspend fun save(value: CapabilityStatusEntity) = dao.putCapabilityStatus(value)
    suspend fun save(value: McpConfigurationEntity) = dao.putMcpConfiguration(value)
    suspend fun save(value: OAuthMetadataEntity) = dao.putOAuthMetadata(value)
    suspend fun save(value: SkillEntity) = dao.putSkill(value)
    suspend fun save(value: SkillVersionEntity) = dao.putSkillVersion(value)
    suspend fun save(value: SkillUpdateAttemptEntity) = dao.putSkillUpdateAttempt(value)
    suspend fun save(value: EscalationEntity) = dao.putEscalation(value)
    suspend fun save(value: ToolExecutionEntity) = dao.putToolExecution(value)
    suspend fun save(value: VerificationOutcomeEntity) = dao.putVerificationOutcome(value)
}

private object EventPayloadCodec {
    fun encode(payload: Map<String, String>): ByteArray = payload.entries.joinToString("\n") { "${it.key}\u0000${it.value}" }.toByteArray(StandardCharsets.UTF_8)
    fun decode(payload: ByteArray): Map<String, String> = payload.toString(StandardCharsets.UTF_8)
        .lineSequence().filter { it.isNotEmpty() }.associate { line -> line.substringBefore('\u0000') to line.substringAfter('\u0000') }
}
