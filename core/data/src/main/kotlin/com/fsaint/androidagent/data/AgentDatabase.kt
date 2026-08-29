package com.fsaint.androidagent.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.PrimaryKey
import com.fsaint.androidagent.model.AgentEvent
import com.fsaint.androidagent.model.AuditRecord
import com.fsaint.androidagent.model.AuthorizationDecision
import com.fsaint.androidagent.model.DeliveryState
import com.fsaint.androidagent.model.VerificationState

@Entity(tableName = "principals") data class PrincipalEntity(@PrimaryKey val id: String, val e164: String, val role: String, val displayName: String?, val content: ByteArray?)
@Entity(tableName = "scope_grants") data class ScopeGrantEntity(@PrimaryKey val id: String, val principalId: String, val resourceType: String, val resourceId: String, val granted: Boolean)
@Entity(tableName = "sessions") data class SessionEntity(@PrimaryKey val id: String, val principalId: String, val scopeId: String, val channel: String, val memoryNamespace: String, val createdAtEpochMs: Long)
@Entity(tableName = "events") data class EventEntity(@PrimaryKey val id: String, val type: String, val source: String, val occurredAtEpochMs: Long, val payload: ByteArray, val deliveryState: String)
@Entity(tableName = "conversation_messages") data class ConversationMessageEntity(@PrimaryKey val id: String, val sessionId: String, val createdAtEpochMs: Long, val content: ByteArray)
@Entity(tableName = "memory_entries") data class MemoryEntryEntity(@PrimaryKey val id: String, val namespace: String, val updatedAtEpochMs: Long, val content: ByteArray)
@Entity(tableName = "schedules") data class ScheduleEntity(@PrimaryKey val id: String, val dueAtEpochMs: Long, val definition: ByteArray, val enabled: Boolean)
@Entity(tableName = "capability_status") data class CapabilityStatusEntity(@PrimaryKey val capabilityId: String, val observedAtEpochMs: Long, val status: ByteArray)
@Entity(tableName = "mcp_configurations") data class McpConfigurationEntity(@PrimaryKey val id: String, val name: String, val configuration: ByteArray)
@Entity(tableName = "oauth_metadata") data class OAuthMetadataEntity(@PrimaryKey val id: String, val mcpConfigurationId: String, val metadata: ByteArray)
@Entity(tableName = "skills") data class SkillEntity(@PrimaryKey val id: String, val activeVersion: String?, val enabled: Boolean)
@Entity(tableName = "skill_versions") data class SkillVersionEntity(@PrimaryKey val id: String, val skillId: String, val version: String, val manifest: ByteArray, val active: Boolean)
@Entity(tableName = "skill_update_attempts") data class SkillUpdateAttemptEntity(@PrimaryKey val id: String, val skillId: String, val attemptedAtEpochMs: Long, val result: ByteArray)
@Entity(tableName = "escalations") data class EscalationEntity(@PrimaryKey val id: String, val sessionId: String, val status: String, val payload: ByteArray)
@Entity(tableName = "tool_executions") data class ToolExecutionEntity(@PrimaryKey val id: String, val sessionId: String, val tool: String, val result: ByteArray, val occurredAtEpochMs: Long)
@Entity(tableName = "verification_outcomes") data class VerificationOutcomeEntity(@PrimaryKey val id: String, val toolExecutionId: String, val state: String, val details: ByteArray?)
@Entity(tableName = "audit_records") data class AuditRecordEntity(@PrimaryKey val id: String, val occurredAtEpochMs: Long, val eventId: String?, val principalId: String?, val scopeId: String?, val sessionId: String?, val skillId: String?, val tool: String, val authorization: String, val verification: String, val result: ByteArray?)

@Dao interface EventDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(entity: EventEntity)
    @Query("SELECT * FROM events WHERE deliveryState != 'COMPLETED' ORDER BY occurredAtEpochMs LIMIT 1") suspend fun nextUndelivered(): EventEntity?
    @Query("UPDATE events SET deliveryState = 'COMPLETED' WHERE id = :id") suspend fun markCompleted(id: String)
}

@Dao interface AuditRecordDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(entity: AuditRecordEntity)
    @Query("SELECT * FROM audit_records ORDER BY occurredAtEpochMs") suspend fun all(): List<AuditRecordEntity>
}

@Dao interface DurableStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putPrincipal(value: PrincipalEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putScopeGrant(value: ScopeGrantEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSession(value: SessionEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putConversation(value: ConversationMessageEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMemory(value: MemoryEntryEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSchedule(value: ScheduleEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putCapabilityStatus(value: CapabilityStatusEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMcpConfiguration(value: McpConfigurationEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putOAuthMetadata(value: OAuthMetadataEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSkill(value: SkillEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSkillVersion(value: SkillVersionEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSkillUpdateAttempt(value: SkillUpdateAttemptEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putEscalation(value: EscalationEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putToolExecution(value: ToolExecutionEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putVerificationOutcome(value: VerificationOutcomeEntity)
}

@Database(entities = [PrincipalEntity::class, ScopeGrantEntity::class, SessionEntity::class, EventEntity::class, ConversationMessageEntity::class, MemoryEntryEntity::class, ScheduleEntity::class, CapabilityStatusEntity::class, McpConfigurationEntity::class, OAuthMetadataEntity::class, SkillEntity::class, SkillVersionEntity::class, SkillUpdateAttemptEntity::class, EscalationEntity::class, ToolExecutionEntity::class, VerificationOutcomeEntity::class, AuditRecordEntity::class], version = 1, exportSchema = true)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun auditRecordDao(): AuditRecordDao
    abstract fun durableStateDao(): DurableStateDao
}

object AgentDatabaseTestFactory {
    fun open(context: Context, name: String): AgentDatabase = Room.databaseBuilder(context, AgentDatabase::class.java, name).allowMainThreadQueries().build()
    fun inMemory(context: Context): AgentDatabase = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java).allowMainThreadQueries().build()
}
