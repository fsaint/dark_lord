package com.fsaint.androidagent.artifacts

import android.content.Context
import com.fsaint.androidagent.model.ToolCall
import com.fsaint.androidagent.model.ToolError
import com.fsaint.androidagent.model.ToolResult
import com.fsaint.androidagent.model.VerificationState
import java.io.File
import java.util.UUID

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
) {
    constructor(context: Context, maxBytes: Int = 8 * 1024 * 1024, ttlMillis: Long = 24 * 60 * 60 * 1000L, clock: () -> Long = System::currentTimeMillis) :
        this(File(context.applicationContext.filesDir, "agent-artifacts"), maxBytes, ttlMillis, clock)

    init { directory.mkdirs() }
    private val metadata = linkedMapOf<String, ArtifactMetadata>()

    @Synchronized
    fun store(bytes: ByteArray, mimeType: String): ArtifactMetadata {
        require(bytes.isNotEmpty() && bytes.size <= maxBytes) { "Artifact exceeds size limit" }
        require(mimeType in ALLOWED_MIME_TYPES) { "Unsupported artifact type" }
        val now = clock()
        cleanupLocked(now)
        val id = "artifact_${UUID.randomUUID()}"
        File(directory, id).writeBytes(bytes)
        return ArtifactMetadata(id, mimeType, bytes.size.toLong(), now, now + ttlMillis).also { metadata[id] = it }
    }

    @Synchronized fun metadata(id: String): ArtifactMetadata? = liveLocked(id)?.first

    @Synchronized fun read(id: String): Pair<ArtifactMetadata, ByteArray>? = liveLocked(id)?.let { (info, file) -> info to file.readBytes() }

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
        if (info.expiresAtEpochMs <= clock()) { deleteLocked(id); return null }
        val file = File(directory, id)
        return if (file.isFile) info to file else null
    }

    private fun cleanupLocked(now: Long) { metadata.keys.toList().forEach { id -> if (metadata[id]!!.expiresAtEpochMs <= now) deleteLocked(id) } }
    private fun deleteLocked(id: String) { metadata.remove(id); File(directory, id).delete() }

    companion object { val ALLOWED_MIME_TYPES = setOf("image/jpeg", "image/png", "application/pdf", "text/plain", "audio/mpeg", "audio/wav") }
}
