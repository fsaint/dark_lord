package com.fsaint.androidagent.data

import com.fsaint.androidagent.model.AgentEvent
import com.fsaint.androidagent.model.AuditRecord
import com.fsaint.androidagent.model.AuthorizationDecision
import com.fsaint.androidagent.model.DeliveryState
import com.fsaint.androidagent.model.PrincipalRole
import com.fsaint.androidagent.model.VerificationState
import com.fsaint.androidagent.policy.Principal
import com.fsaint.androidagent.policy.PrincipalDirectory
import com.fsaint.androidagent.runtime.AuditStore
import com.fsaint.androidagent.runtime.EventStore
import com.fsaint.androidagent.runtime.Escalation
import com.fsaint.androidagent.runtime.EscalationStore
import com.fsaint.androidagent.runtime.OwnerDecision
import java.nio.charset.StandardCharsets

class EventRepository(private val dao: EventDao) : EventStore {
    override suspend fun enqueue(event: AgentEvent) {
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

    override suspend fun markCompleted(eventId: String) = dao.markCompleted(eventId)
}

class AuditRepository(private val dao: AuditRecordDao) : AuditStore {
    override suspend fun append(record: AuditRecord) {
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

class PrincipalRepository(
    private val dao: DurableStateDao,
) : PrincipalDirectory {
    override suspend fun owner(): Principal? = dao.owner()?.toPrincipal()

    override suspend fun provisionInitialOwner(e164: String): Principal {
        require(E164.matches(e164)) { "Initial owner requires an E.164 number" }
        return dao.provisionInitialOwner(
            PrincipalEntity(
                id = "owner:$e164",
                e164 = e164,
                role = PrincipalRole.OWNER.name,
                displayName = "Owner",
                content = null,
            ),
        ).toPrincipal()
    }

    override suspend fun lookup(e164: String): Principal {
        val source = e164.trim()
        return dao.principalByE164(source)?.toPrincipal()
            ?: Principal("unknown:$source", source, PrincipalRole.UNKNOWN)
    }

    override suspend fun list(): List<Principal> = dao.principals().map { it.toPrincipal() }

    override suspend fun upsert(principal: Principal) {
        val e164 = requireNotNull(principal.e164) { "Persisted principals require an E.164 number" }
        val normalized = e164.trim()
        require(E164.matches(normalized)) { "Persisted principals require an E.164 number" }
        val existing = dao.principalByE164(normalized)
        require(existing == null || existing.id == principal.id) { "A principal already uses $normalized" }
        dao.putPrincipal(
            PrincipalEntity(
                id = principal.id,
                e164 = normalized,
                role = principal.role.name,
                displayName = principal.id,
                content = null,
            ),
        )
    }

    override suspend fun removeKnown(e164: String): Boolean = dao.deleteKnown(e164.trim()) > 0

    private fun PrincipalEntity.toPrincipal(): Principal = Principal(id, e164, PrincipalRole.valueOf(role))

    private companion object {
        val E164 = Regex("\\+[1-9]\\d{1,14}")
    }
}

class EscalationRepository(private val dao: DurableStateDao) : EscalationStore {
    override suspend fun save(escalation: Escalation) {
        dao.putEscalation(EscalationEntity(escalation.id, escalation.sessionId, "OPEN", EventPayloadCodec.encode(mapOf("channel" to escalation.channel, "recipient" to escalation.recipient, "question" to escalation.question, "reason" to escalation.reason, "proposedAction" to escalation.proposedAction))))
    }

    override suspend fun resolve(id: String, decision: OwnerDecision): Escalation? {
        val entity = dao.escalation(id) ?: return null
        if (entity.status != "OPEN") return null
        dao.updateEscalationStatus(id, decision.name)
        val fields = EventPayloadCodec.decode(entity.payload)
        val channel = fields["channel"] ?: entity.sessionId.substringAfterLast(':', "SMS")
        return Escalation(entity.id, entity.sessionId, channel, fields.getValue("recipient"), fields.getValue("question"), fields.getValue("reason"), fields.getValue("proposedAction"))
    }
}

private object EventPayloadCodec {
    fun encode(payload: Map<String, String>): ByteArray = payload.entries.joinToString("\n") { "${it.key}\u0000${it.value}" }.toByteArray(StandardCharsets.UTF_8)
    fun decode(payload: ByteArray): Map<String, String> = payload.toString(StandardCharsets.UTF_8)
        .lineSequence().filter { it.isNotEmpty() }.associate { line -> line.substringBefore('\u0000') to line.substringAfter('\u0000') }
}
