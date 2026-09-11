package com.fsaint.androidagent.data

import androidx.room.*
import com.fsaint.androidagent.runtime.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

@Entity(tableName = "chats")
data class ChatEntity(@PrimaryKey val id: String, val ownerId: String, val title: String, val createdAt: Long, val updatedAt: Long, val archived: Boolean)
@Entity(tableName = "outside_chats")
data class OutsideChatEntity(@PrimaryKey val ownerId: String, val chatId: String)
@Entity(tableName = "chat_requests", indices = [Index(value = ["chatId"])])
data class ChatRequestEntity(@PrimaryKey val id: String, val chatId: String, val source: String, val state: String)
@Entity(tableName = "chat_messages", indices = [Index(value = ["chatId", "sequence"], unique = true)])
data class ChatMessageEntity(@PrimaryKey val id: String, val chatId: String, val requestId: String, val sequence: Long, val role: String, val text: String)
@Entity(tableName = "chat_attachments")
data class ChatAttachmentEntity(@PrimaryKey val messageId: String, val artifactId: String)
data class ChatArtifactReference(val artifactId: String, val requestId: String)

@Dao
interface ChatDao {
    @Query("SELECT c.*, CASE WHEN o.chatId=c.id THEN 1 ELSE 0 END AS outside FROM chats c LEFT JOIN outside_chats o ON o.ownerId=c.ownerId WHERE c.ownerId=:ownerId ORDER BY outside DESC, c.updatedAt DESC")
    suspend fun list(ownerId: String): List<Chat>
    @Query("SELECT c.*, CASE WHEN o.chatId=c.id THEN 1 ELSE 0 END AS outside FROM chats c LEFT JOIN outside_chats o ON o.ownerId=c.ownerId WHERE c.ownerId=:ownerId ORDER BY outside DESC, c.updatedAt DESC")
    fun observe(ownerId: String): Flow<List<Chat>>
    @Query("SELECT * FROM chats WHERE id=:id") suspend fun chat(id: String): ChatEntity?
    @Query("SELECT chatId FROM outside_chats WHERE ownerId=:ownerId") suspend fun outside(ownerId: String): String?
    @Insert suspend fun insertChat(chat: ChatEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun setOutside(outside: OutsideChatEntity)
    @Query("UPDATE chats SET archived=1 WHERE id=:id") suspend fun archive(id: String)
    @Query("UPDATE chats SET title=:title, updatedAt=:now WHERE id=:id") suspend fun rename(id: String, title: String, now: Long)
    @Query("UPDATE chats SET updatedAt=:now WHERE id=:id") suspend fun touch(id: String, now: Long)
    @Query("SELECT m.id,m.requestId,m.sequence,m.role,m.text,a.artifactId FROM chat_messages m LEFT JOIN chat_attachments a ON a.messageId=m.id WHERE m.chatId=:id ORDER BY m.sequence")
    suspend fun messages(id: String): List<ChatMessage>
    @Query("SELECT m.id,m.requestId,m.sequence,m.role,m.text,a.artifactId FROM chat_messages m LEFT JOIN chat_attachments a ON a.messageId=m.id JOIN chats c ON c.id=m.chatId WHERE m.chatId=:id AND c.ownerId=:ownerId ORDER BY m.sequence")
    fun observeMessages(ownerId: String, id: String): Flow<List<ChatMessage>>
    @Query("SELECT r.* FROM chat_requests r JOIN chats c ON c.id=r.chatId WHERE r.chatId=:id AND c.ownerId=:ownerId")
    fun observeRequests(ownerId: String, id: String): Flow<List<ChatRequest>>
    @Query("SELECT * FROM chat_requests WHERE id=:id") suspend fun request(id: String): ChatRequestEntity?
    @Query("SELECT COUNT(*) FROM chat_requests WHERE chatId=:id AND state='RUNNING'") suspend fun active(id: String): Int
    @Insert suspend fun insertRequest(request: ChatRequestEntity)
    @Insert suspend fun insertMessage(message: ChatMessageEntity)
    @Insert suspend fun insertAttachment(attachment: ChatAttachmentEntity)
    @Query("SELECT a.artifactId,m.requestId FROM chat_attachments a JOIN chat_messages m ON m.id=a.messageId") suspend fun attachmentReferences(): List<ChatArtifactReference>
    @Query("SELECT COUNT(*) FROM chat_messages WHERE id=:id") suspend fun messageExists(id: String): Int
    @Query("SELECT COALESCE(MAX(sequence), -1)+1 FROM chat_messages WHERE chatId=:id") suspend fun nextSequence(id: String): Long
    @Query("UPDATE chat_requests SET state=:state WHERE id=:id") suspend fun finish(id: String, state: String)
    @Query("UPDATE chat_requests SET state='INTERRUPTED' WHERE state='RUNNING'") suspend fun recover()
    @Query("SELECT * FROM chat_requests WHERE state='RUNNING'") suspend fun runningRequests(): List<ChatRequestEntity>
}

class ChatRepository(private val database: AgentDatabase, private val clock: () -> Long = System::currentTimeMillis) : ChatStore {
    private val dao get() = database.chatDao()
    private suspend fun owned(ownerId: String, chatId: String): ChatEntity = requireNotNull(dao.chat(chatId)?.takeIf { it.ownerId == ownerId }) { "Chat unavailable" }
    private suspend fun createLocked(ownerId: String, title: String): ChatEntity {
        require(ownerId.isNotBlank())
        val clean = title.trim().also { require(it.isNotEmpty() && it.length <= 100) { "Use a name between 1 and 100 characters" } }
        return ChatEntity(UUID.randomUUID().toString(), ownerId, clean, clock(), clock(), false).also { dao.insertChat(it) }
    }
    private fun ChatEntity.model(outside: Boolean = false) = Chat(id, ownerId, title, createdAt, updatedAt, archived, outside)
    override suspend fun outside(ownerId: String): Chat = database.withTransaction {
        dao.outside(ownerId)?.let { owned(ownerId, it).model(true) } ?: createLocked(ownerId, "Outside chat").also { dao.setOutside(OutsideChatEntity(ownerId, it.id)) }.model(true)
    }
    override suspend fun newOutside(ownerId: String): Chat = database.withTransaction {
        dao.outside(ownerId)?.let { require(dao.active(it) == 0) { "Stop the current chat first" }; dao.archive(it) }
        createLocked(ownerId, "Outside chat").also { dao.setOutside(OutsideChatEntity(ownerId, it.id)) }.model(true)
    }
    override suspend fun create(ownerId: String, title: String): Chat = database.withTransaction { createLocked(ownerId, title).model() }
    override suspend fun rename(ownerId: String, chatId: String, title: String) = database.withTransaction {
        owned(ownerId, chatId)
        require(title.trim().isNotEmpty() && title.trim().length <= 100)
        dao.rename(chatId, title.trim(), clock())
    }
    override suspend fun list(ownerId: String) = dao.list(ownerId)
    override fun observe(ownerId: String) = dao.observe(ownerId)
    override suspend fun messages(ownerId: String, chatId: String): List<ChatMessage> { owned(ownerId, chatId); return dao.messages(chatId) }
    override fun observeMessages(ownerId: String, chatId: String) = dao.observeMessages(ownerId, chatId)
    override fun observeRequests(ownerId: String, chatId: String) = dao.observeRequests(ownerId, chatId)
    override suspend fun begin(ownerId: String, chatId: String, requestId: String, text: String, source: String, artifactId: String?, supersede: Boolean, current: () -> Boolean): Boolean = database.withTransaction {
        checkCurrent(current)
        require(text.isNotBlank() && text.length <= 16_384)
        require(!owned(ownerId, chatId).archived) { "This chat is archived" }
        dao.request(requestId)?.let { require(it.chatId == chatId); return@withTransaction false }
        if (supersede) {
            require(source in setOf("CAPTURE", "VOICE") && dao.outside(ownerId) == chatId)
            dao.runningRequests().filter { it.chatId == chatId }.forEach { old ->
                val saved = dao.messages(chatId).any { it.requestId == old.id && it.artifactId != null }
                val status = if (old.source == "CAPTURE" && !saved) "Photo capture interrupted. No new photo was saved; any earlier photos belong to previous captures."
                    else "Request interrupted by a new side-button interaction. Saved photos and recorded outcomes remain available. In-flight actions without a recorded outcome have unknown completion; do not automatically retry them."
                dao.insertMessage(ChatMessageEntity(UUID.randomUUID().toString(), chatId, old.id, dao.nextSequence(chatId), "status", status))
                dao.finish(old.id, "INTERRUPTED")
            }
        }
        require(dao.active(chatId) == 0) { "This chat is busy. Stop it before sending another message." }
        checkCurrent(current)
        dao.insertRequest(ChatRequestEntity(requestId, chatId, source, "RUNNING"))
        val messageId = UUID.randomUUID().toString()
        dao.insertMessage(ChatMessageEntity(messageId, chatId, requestId, dao.nextSequence(chatId), "user", text))
        artifactId?.let { dao.insertAttachment(ChatAttachmentEntity(messageId, it)) }
        dao.touch(chatId, clock())
        checkCurrent(current) // Roll back admission if superseded during a suspending write.
        true
    }
    override suspend fun finish(ownerId: String, requestId: String, text: String, state: String) = database.withTransaction {
        require(state in setOf("COMPLETE", "FAILED", "INTERRUPTED"))
        val request = requireNotNull(dao.request(requestId))
        owned(ownerId, request.chatId)
        if (request.state != "RUNNING") return@withTransaction
        if (text.isNotBlank()) dao.insertMessage(ChatMessageEntity(UUID.randomUUID().toString(), request.chatId, requestId, dao.nextSequence(request.chatId), if (state == "COMPLETE") "assistant" else "status", text))
        dao.finish(requestId, state)
        dao.touch(request.chatId, clock())
    }
    override suspend fun attachPhoto(ownerId: String, requestId: String, artifactId: String, current: () -> Boolean) = database.withTransaction {
        checkCurrent(current)
        val request = requireNotNull(dao.request(requestId))
        owned(ownerId, request.chatId)
        require(request.state == "RUNNING" && request.source == "CAPTURE")
        val message = dao.messages(request.chatId).first { it.requestId == requestId && it.role == "user" }
        require(message.artifactId == null) { "Photo already attached" }
        checkCurrent(current)
        dao.insertAttachment(ChatAttachmentEntity(message.id, artifactId))
        checkCurrent(current) // A stale capture must not become the latest retained image.
    }
    private fun checkCurrent(current: () -> Boolean) {
        if (!current()) throw kotlinx.coroutines.CancellationException("Interaction superseded")
    }
    override suspend fun recordTool(ownerId: String, requestId: String, executionId: String, text: String) = database.withTransaction {
        val request = requireNotNull(dao.request(requestId))
        owned(ownerId, request.chatId)
        val messageId = "tool:$requestId:$executionId"
        if (dao.messageExists(messageId) == 0) dao.insertMessage(ChatMessageEntity(messageId, request.chatId, requestId, dao.nextSequence(request.chatId), "tool", text.take(16_384)))
    }
    override suspend fun request(ownerId: String, requestId: String): ChatRequest? = dao.request(requestId)?.let {
        owned(ownerId, it.chatId); ChatRequest(it.id, it.chatId, it.source, it.state)
    }
    override suspend fun recover() = database.withTransaction {
        dao.runningRequests().forEach { request ->
            val saved = dao.messages(request.chatId).any { it.requestId == request.id && it.artifactId != null }
            val text = if (request.source == "CAPTURE" && !saved) "Photo capture was interrupted by restart. No new photo was saved; any earlier photos belong to previous captures."
                else "Request interrupted by restart. Saved photos and recorded outcomes remain available. Any in-flight action without a recorded outcome has unknown completion; do not automatically retry it."
            dao.insertMessage(ChatMessageEntity(UUID.randomUUID().toString(), request.chatId, request.id, dao.nextSequence(request.chatId), "status", text))
        }
        dao.recover()
    }
    suspend fun attachmentReferences(): Map<String, Set<String>> = dao.attachmentReferences().groupBy { it.artifactId }.mapValues { (_, refs) -> refs.map { "chat:${it.requestId}" }.toSet() }
}
