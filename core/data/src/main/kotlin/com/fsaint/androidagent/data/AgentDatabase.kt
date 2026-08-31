package com.fsaint.androidagent.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.migration.Migration
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.PrimaryKey
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.fsaint.androidagent.model.AgentEvent
import com.fsaint.androidagent.model.AuditRecord
import com.fsaint.androidagent.model.AuthorizationDecision
import com.fsaint.androidagent.model.DeliveryState
import com.fsaint.androidagent.model.VerificationState

@Entity(tableName = "principals", indices = [Index(value = ["e164"], unique = true)]) data class PrincipalEntity(@PrimaryKey val id: String, val e164: String, val role: String, val displayName: String?, val content: ByteArray?)
@Entity(tableName = "scope_grants") data class ScopeGrantEntity(@PrimaryKey val id: String, val principalId: String, val resourceType: String, val resourceId: String, val granted: Boolean)
@Entity(tableName = "sessions") data class SessionEntity(@PrimaryKey val id: String, val principalId: String, val scopeId: String, val channel: String, val memoryNamespace: String, val createdAtEpochMs: Long)
@Entity(tableName = "events") data class EventEntity(@PrimaryKey val id: String, val type: String, val source: String, val occurredAtEpochMs: Long, val payload: ByteArray, val deliveryState: String)
@Entity(tableName = "pending_replies") data class PendingReplyEntity(@PrimaryKey val eventId: String, val channel: String, val recipient: String, val text: ByteArray)
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
    @Query("SELECT EXISTS(SELECT 1 FROM events WHERE id = :id)") suspend fun contains(id: String): Boolean
    @Query("SELECT deliveryState FROM events WHERE id = :id") suspend fun deliveryState(id: String): String?
    @Query("SELECT * FROM events WHERE deliveryState != 'COMPLETED' ORDER BY occurredAtEpochMs LIMIT 1") suspend fun nextUndelivered(): EventEntity?
    @Query("UPDATE events SET deliveryState = 'COMPLETED' WHERE id = :id") suspend fun markCompleted(id: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putPendingReply(entity: PendingReplyEntity)
    @Query("SELECT * FROM pending_replies WHERE eventId = :eventId") suspend fun pendingReply(eventId: String): PendingReplyEntity?
    @Query("DELETE FROM pending_replies WHERE eventId = :eventId") suspend fun deletePendingReply(eventId: String)
}

@Dao interface AuditRecordDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(entity: AuditRecordEntity)
    @Query("SELECT * FROM audit_records ORDER BY occurredAtEpochMs") suspend fun all(): List<AuditRecordEntity>
}

@Dao interface DurableStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putPrincipal(value: PrincipalEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertPrincipal(value: PrincipalEntity)
    @Query("SELECT * FROM principals WHERE e164 = :e164 LIMIT 1") suspend fun principalByE164(e164: String): PrincipalEntity?
    @Query("SELECT * FROM principals WHERE role = 'OWNER' LIMIT 1") suspend fun owner(): PrincipalEntity?
    @Transaction
    suspend fun provisionInitialOwner(value: PrincipalEntity): PrincipalEntity {
        require(owner() == null) { "An owner is already provisioned" }
        require(principalByE164(value.e164) == null) { "A principal already uses ${value.e164}" }
        insertPrincipal(value)
        return value
    }
    @Query("SELECT * FROM principals ORDER BY role, displayName") suspend fun principals(): List<PrincipalEntity>
    @Query("DELETE FROM principals WHERE e164 = :e164 AND role = 'KNOWN'") suspend fun deleteKnown(e164: String): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putScopeGrant(value: ScopeGrantEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSession(value: SessionEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putConversation(value: ConversationMessageEntity)
    @Query("SELECT * FROM conversation_messages WHERE sessionId = :sessionId ORDER BY createdAtEpochMs, id")
    suspend fun conversation(sessionId: String): List<ConversationMessageEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMemory(value: MemoryEntryEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSchedule(value: ScheduleEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putCapabilityStatus(value: CapabilityStatusEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMcpConfiguration(value: McpConfigurationEntity)
    @Query("SELECT * FROM mcp_configurations ORDER BY name") suspend fun mcpConfigurations(): List<McpConfigurationEntity>
    @Query("DELETE FROM mcp_configurations WHERE id = :id") suspend fun deleteMcpConfiguration(id: String): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putOAuthMetadata(value: OAuthMetadataEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSkill(value: SkillEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSkillVersion(value: SkillVersionEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSkillUpdateAttempt(value: SkillUpdateAttemptEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putEscalation(value: EscalationEntity)
    @Query("SELECT * FROM escalations WHERE id = :id") suspend fun escalation(id: String): EscalationEntity?
    @Query("UPDATE escalations SET status = :status WHERE id = :id") suspend fun updateEscalationStatus(id: String, status: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putToolExecution(value: ToolExecutionEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putVerificationOutcome(value: VerificationOutcomeEntity)
}

@Database(entities = [PrincipalEntity::class, ScopeGrantEntity::class, SessionEntity::class, EventEntity::class, PendingReplyEntity::class, ConversationMessageEntity::class, MemoryEntryEntity::class, ScheduleEntity::class, CapabilityStatusEntity::class, McpConfigurationEntity::class, OAuthMetadataEntity::class, SkillEntity::class, SkillVersionEntity::class, SkillUpdateAttemptEntity::class, EscalationEntity::class, ToolExecutionEntity::class, VerificationOutcomeEntity::class, AuditRecordEntity::class], version = 4, exportSchema = true)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun auditRecordDao(): AuditRecordDao
    abstract fun durableStateDao(): DurableStateDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_principals_e164 ON principals (e164)")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM principals WHERE role = 'OWNER' AND rowid NOT IN (SELECT MIN(rowid) FROM principals WHERE role = 'OWNER' GROUP BY e164)")
                db.execSQL("DELETE FROM principals WHERE role = 'KNOWN' AND rowid NOT IN (SELECT MIN(rowid) FROM principals WHERE role = 'KNOWN' GROUP BY e164)")
                db.execSQL("DELETE FROM principals WHERE role = 'KNOWN' AND e164 IN (SELECT e164 FROM principals WHERE role = 'OWNER')")
                db.execSQL("DROP INDEX IF EXISTS index_principals_e164")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_principals_e164 ON principals (e164)")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS pending_replies (eventId TEXT NOT NULL, channel TEXT NOT NULL, recipient TEXT NOT NULL, text BLOB NOT NULL, PRIMARY KEY(eventId))")
            }
        }
    }
}

object AgentDatabaseTestFactory {
    fun open(context: Context, name: String): AgentDatabase = Room.databaseBuilder(context, AgentDatabase::class.java, name)
        .addMigrations(AgentDatabase.MIGRATION_1_2, AgentDatabase.MIGRATION_2_3, AgentDatabase.MIGRATION_3_4)
        .allowMainThreadQueries()
        .build()
    fun inMemory(context: Context): AgentDatabase = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java).allowMainThreadQueries().build()
}
