package com.fsaint.androidagent.artifacts

import android.content.Context
import com.fsaint.androidagent.model.ToolCall
import com.fsaint.androidagent.model.ToolError
import com.fsaint.androidagent.model.ToolResult
import com.fsaint.androidagent.model.VerificationState
import java.io.File
import java.util.UUID
import java.util.Properties
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class ArtifactMetadata(
    val id: String,
    val mimeType: String,
    val sizeBytes: Long,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long,
)

/** Owner-scoped, bounded artifact storage. Models receive opaque IDs, never filesystem paths. */
class ArtifactStore(
    private val directory: File,
    private val maxBytes: Int = 8 * 1024 * 1024,
    private val ttlMillis: Long = 24 * 60 * 60 * 1000L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxTotalBytes: Long = 128L * 1024 * 1024,
) {
    constructor(context: Context, maxBytes: Int = 8 * 1024 * 1024, ttlMillis: Long = 24 * 60 * 60 * 1000L, clock: () -> Long = System::currentTimeMillis) :
        this(File(context.applicationContext.filesDir, "agent-artifacts"), maxBytes, ttlMillis, clock)

    init { directory.mkdirs() }
    private val metadata = linkedMapOf<String, ArtifactMetadata>()
    private val references = mutableMapOf<String, MutableSet<String>>()

    init {
        directory.listFiles()?.filter { it.name.endsWith(".meta") }?.forEach { file ->
            runCatching {
                val id = file.name.removeSuffix(".meta")
                require(validId(id))
                val p = Properties().apply { file.inputStream().use { load(it) } }
                val info = ArtifactMetadata(id, p.getProperty("mime"), p.getProperty("size").toLong(), p.getProperty("created").toLong(), p.getProperty("expires").toLong())
                require(info.mimeType in ALLOWED_MIME_TYPES && info.sizeBytes in 1..maxBytes.toLong())
                require(File(directory, id).length() == info.sizeBytes)
                metadata[id] = info
                references[id] = p.stringPropertyNames().filter { it.startsWith("ref.") }.map { p.getProperty(it) }.toMutableSet()
            }
        }
    }

    @Synchronized
    fun store(bytes: ByteArray, mimeType: String): ArtifactMetadata {
        require(bytes.isNotEmpty() && bytes.size <= maxBytes) { "Artifact exceeds size limit" }
        require(mimeType in ALLOWED_MIME_TYPES) { "Unsupported artifact type" }
        val now = clock()
        cleanupLocked(now)
        val used = directory.listFiles()?.filter { validId(it.name) || validId(it.name.removeSuffix(".tmp")) || validId(it.name.removeSuffix(".meta.tmp")) }?.sumOf { it.length() } ?: 0
        require(used + bytes.size <= maxTotalBytes) { "Artifact storage is full. Free space before taking another picture." }
        val id = "artifact_${UUID.randomUUID()}"
        val temporary = File(directory, "$id.tmp")
        temporary.writeBytes(bytes)
        Files.move(temporary.toPath(), File(directory, id).toPath(), StandardCopyOption.REPLACE_EXISTING)
        return ArtifactMetadata(id, mimeType, bytes.size.toLong(), now, now + ttlMillis).also {
            metadata[id] = it
            try { persist(id) } catch (error: Exception) { deleteLocked(id); throw error }
        }
    }

    @Synchronized fun retain(id: String, reference: String) {
        require(reference.isNotBlank() && reference.length <= 200)
        requireNotNull(liveLocked(id)) { "Photo is no longer available" }
        references.getOrPut(id) { mutableSetOf() }.add(reference)
        persist(id)
    }

    @Synchronized fun release(id: String, reference: String) {
        if (metadata[id] == null) return
        references[id]?.remove(reference)
        persist(id)
    }

    /** Reconcile only chat-owned pins after Room recovery; other consumers retain their own pins. */
    @Synchronized fun reconcileChatReferences(committed: Map<String, Set<String>>) {
        metadata.keys.forEach { id ->
            val refs = references.getOrPut(id) { mutableSetOf() }
            refs.removeAll { it.startsWith("chat:") }
            refs.addAll(committed[id].orEmpty())
            persist(id)
        }
        cleanupLocked(clock())
    }

    @Synchronized fun metadata(id: String): ArtifactMetadata? = liveLocked(id)?.first

    @Synchronized fun read(id: String): Pair<ArtifactMetadata, ByteArray>? = liveLocked(id)?.let { (info, file) -> info to file.readBytes() }

    @Synchronized
    fun latest(mimePrefix: String? = null): Pair<ArtifactMetadata, ByteArray>? = metadata.values
        .asSequence()
        .filter { mimePrefix == null || it.mimeType.startsWith(mimePrefix) }
        .maxByOrNull { it.createdAtEpochMs }
        ?.let { info -> read(info.id) }

    @Synchronized fun cleanup() { cleanupLocked(clock()) }

    fun handlers(): Map<String, suspend (ToolCall) -> ToolResult<Any>> = mapOf(
        "artifact.metadata" to { call ->
            metadata(call.arguments["artifactId"].orEmpty())?.let { ToolResult(true, it, verification = VerificationState.VERIFIED) }
                ?: ToolResult(false, error = ToolError.NOT_FOUND)
        },
        "artifact.open" to { call ->
            read(call.arguments["artifactId"].orEmpty())?.second?.let { ToolResult(true, it, verification = VerificationState.VERIFIED) }
                ?: ToolResult(false, error = ToolError.NOT_FOUND)
        },
    )

    private fun liveLocked(id: String): Pair<ArtifactMetadata, File>? {
        val info = metadata[id] ?: return null
        if (info.expiresAtEpochMs <= clock() && references[id].isNullOrEmpty()) { deleteLocked(id); return null }
        val file = File(directory, id)
        return if (file.isFile) info to file else null
    }

    private fun cleanupLocked(now: Long) {
        metadata.keys.toList().forEach { id -> if (metadata[id]!!.expiresAtEpochMs <= now && references[id].isNullOrEmpty()) deleteLocked(id) }
        // Only remove unindexed orphan writes after the normal retention period.
        directory.listFiles()?.filter { validId(it.name) && it.name !in metadata && !File(directory, "${it.name}.meta").exists() && now - it.lastModified() > ttlMillis }
            ?.forEach { it.delete() }
        directory.listFiles()?.filter { (validId(it.name.removeSuffix(".tmp")) || validId(it.name.removeSuffix(".meta.tmp"))) && now - it.lastModified() > ttlMillis && it.name.endsWith(".tmp") }
            ?.forEach { it.delete() }
    }
    private fun deleteLocked(id: String) { metadata.remove(id); references.remove(id); File(directory, id).delete(); File(directory, "$id.meta").delete() }
    private fun validId(id: String) = id.matches(Regex("artifact_[a-fA-F0-9-]{36}"))
    private fun persist(id: String) {
        val info = requireNotNull(metadata[id])
        val p = Properties().apply {
            setProperty("mime", info.mimeType); setProperty("size", info.sizeBytes.toString())
            setProperty("created", info.createdAtEpochMs.toString()); setProperty("expires", info.expiresAtEpochMs.toString())
            references[id].orEmpty().forEachIndexed { index, ref -> setProperty("ref.$index", ref) }
        }
        val temporary = File(directory, "$id.meta.tmp")
        temporary.outputStream().use { p.store(it, null); it.fd.sync() }
        Files.move(temporary.toPath(), File(directory, "$id.meta").toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object { val ALLOWED_MIME_TYPES = setOf("image/jpeg", "image/png", "application/pdf", "text/plain", "audio/mpeg", "audio/wav", "audio/mp4", "video/mp4", "video/webm") }
}
