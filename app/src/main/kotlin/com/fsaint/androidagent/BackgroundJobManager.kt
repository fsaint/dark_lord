package com.fsaint.androidagent

import android.content.Context
import androidx.core.content.ContextCompat
import com.fsaint.androidagent.artifacts.ArtifactMetadata
import com.fsaint.androidagent.artifacts.ArtifactStore
import com.fsaint.androidagent.capabilities.audio.MicrophoneCapability
import com.fsaint.androidagent.capabilities.audio.MicrophoneClip
import com.fsaint.androidagent.capabilities.audio.MicrophoneStartRequest
import com.fsaint.androidagent.capabilities.camera.VideoClip
import com.fsaint.androidagent.capabilities.camera.VideoStartRequest
import com.fsaint.androidagent.model.ToolCall
import com.fsaint.androidagent.model.ToolError
import com.fsaint.androidagent.model.ToolResult
import com.fsaint.androidagent.model.VerificationState
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable

enum class BackgroundJobStatus { STARTING, RUNNING, STOPPING, COMPLETED, FAILED, CANCELLED, INTERRUPTED }

data class BackgroundJob(
    val id: String,
    val type: String,
    val status: BackgroundJobStatus,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val config: Map<String, String> = emptyMap(),
    val progress: String? = null,
    val artifact: ArtifactMetadata? = null,
    val error: String? = null,
)

interface BackgroundJobStateStore { fun load(): List<BackgroundJob>; fun save(job: BackgroundJob) }
interface MediaJobForeground { fun start(jobId: String); fun stop(jobId: String) }

internal class SharedPreferencesBackgroundJobStateStore(context: Context) : BackgroundJobStateStore {
    private val preferences = context.getSharedPreferences("background_jobs", Context.MODE_PRIVATE)
    override fun load(): List<BackgroundJob> = preferences.all.keys.filter { it.endsWith(".type") }.mapNotNull { key ->
        val id = key.removeSuffix(".type")
        val type = preferences.getString(key, null) ?: return@mapNotNull null
        val status = preferences.getString("$id.status", null)?.let { runCatching { BackgroundJobStatus.valueOf(it) }.getOrNull() } ?: BackgroundJobStatus.INTERRUPTED
        BackgroundJob(id, type, status, preferences.getLong("$id.created", System.currentTimeMillis()), preferences.getLong("$id.updated", System.currentTimeMillis()))
    }
    override fun save(job: BackgroundJob) { preferences.edit().putString("${job.id}.type", job.type).putString("${job.id}.status", job.status.name).putLong("${job.id}.created", job.createdAtEpochMs).putLong("${job.id}.updated", job.updatedAtEpochMs).apply() }
}

internal class AndroidMediaJobForeground(private val context: Context) : MediaJobForeground {
    override fun start(jobId: String) { ContextCompat.startForegroundService(context, AgentRuntimeService.mediaIntent(context, jobId)) }
    override fun stop(jobId: String) { context.startService(AgentRuntimeService.stopMediaIntent(context, jobId)) }
}

/** Unified lifecycle for work that outlives one model/tool turn. */
class BackgroundJobManager(
    private val artifacts: ArtifactStore,
    private val microphone: MicrophoneCapability,
    private val scope: CoroutineScope,
    private val startVideo: suspend (VideoStartRequest) -> ToolResult<Unit>,
    private val stopVideo: suspend () -> ToolResult<VideoClip>,
    private val stateStore: BackgroundJobStateStore,
    private val mediaForeground: MediaJobForeground,
    private val pythonExecutor: (suspend (Map<String, String>) -> ToolResult<Any>)? = null,
) {
    private val jobs = ConcurrentHashMap<String, BackgroundJob>()
    private val runners = ConcurrentHashMap<String, Job>()
    private val lock = Any()

    init {
        // A process death must never make media appear to have completed.
        stateStore.load().forEach { stored ->
            val restored = if (stored.type in MEDIA_TYPES && stored.status in ACTIVE) stored.copy(status = BackgroundJobStatus.INTERRUPTED, updatedAtEpochMs = now(), error = "Process stopped before the job completed") else stored
            jobs[restored.id] = restored
            if (restored != stored) stateStore.save(restored)
        }
    }

    fun handlers(): Map<String, suspend (ToolCall) -> ToolResult<Any>> = mapOf(
        "jobs.start" to { call -> start(call) },
        "jobs.status" to { call -> status(call.arguments["jobId"]) },
        "jobs.list" to { list() },
        "jobs.stop" to { call -> stop(call.arguments["jobId"], call.arguments["type"]) },
        "jobs.cancel" to { call -> cancel(call.arguments["jobId"]) },
    )

    suspend fun start(call: ToolCall): ToolResult<Any> {
        val type = call.arguments["type"]?.trim()?.lowercase().orEmpty()
        if (type !in SUPPORTED_TYPES) return ToolResult(false, error = ToolError.UNSUPPORTED)
        synchronized(lock) {
            if (type in EXCLUSIVE_TYPES && jobs.values.any { it.type in EXCLUSIVE_TYPES && it.status in ACTIVE }) return ToolResult(false, error = ToolError.DEVICE_BUSY)
            val id = "job_${UUID.randomUUID()}"
            val job = BackgroundJob(id, type, BackgroundJobStatus.STARTING, now(), now(), call.arguments - "type")
            jobs[id] = job
            stateStore.save(job)
            runners[id] = scope.launch { run(id) }
            return ToolResult(true, job.toPayload(), verification = VerificationState.VERIFIED)
        }
    }

    fun status(id: String?): ToolResult<Any> = id?.let { jobs[it] }?.let { ToolResult(true, it.toPayload(), verification = VerificationState.VERIFIED) }
        ?: ToolResult(false, error = ToolError.NOT_FOUND)

    fun list(): ToolResult<Any> = ToolResult(true, jobs.values.sortedByDescending { it.createdAtEpochMs }.map { it.toPayload() }, verification = VerificationState.VERIFIED)

    suspend fun stop(id: String?, type: String?): ToolResult<Any> {
        val target = id?.let(jobs::get) ?: jobs.values.filter { it.type == type && it.status in ACTIVE }.maxByOrNull { it.createdAtEpochMs }
        ?: return ToolResult(false, error = ToolError.NOT_FOUND)
        if (target.status !in ACTIVE) return ToolResult(true, target.toPayload(), verification = VerificationState.VERIFIED)
        update(target.copy(status = BackgroundJobStatus.STOPPING))
        runners[target.id]?.cancelAndJoin()
        return status(target.id)
    }

    suspend fun cancel(id: String?): ToolResult<Any> = id?.let(jobs::get)?.let { target ->
        runners[target.id]?.cancelAndJoin()
        val cancelled = target.copy(status = BackgroundJobStatus.CANCELLED, updatedAtEpochMs = now())
        update(cancelled)
        ToolResult(true, cancelled.toPayload(), verification = VerificationState.VERIFIED)
    } ?: ToolResult(false, error = ToolError.NOT_FOUND)

    private suspend fun run(id: String) {
        val initial = jobs[id] ?: return
        update(initial.copy(status = BackgroundJobStatus.RUNNING))
        try {
            when (initial.type) {
                "audio" -> runAudio(initial)
                "python" -> {
                    val executor = pythonExecutor ?: throw IllegalStateException("python worker unavailable")
                    val result = executor(initial.config)
                    if (!result.success) throw IllegalStateException(result.error?.name ?: "python execution failed")
                    update(jobs[id]!!.copy(progress = result.payload?.toString()?.take(2_000)))
                }
                "video" -> runVideo(initial)
                "sensor_log", "bluetooth_log", "wifi_log" -> delay(initial.config["durationMs"]?.toLongOrNull()?.coerceIn(1, 3_600_000) ?: 10_000)
            }
            if (jobs[id]?.status == BackgroundJobStatus.RUNNING) update(jobs[id]!!.copy(status = BackgroundJobStatus.COMPLETED))
        } catch (_: CancellationException) {
            if (initial.type == "video") {
                val current = jobs[id] ?: return
                try {
                    val artifact = storeVideo(withContext(NonCancellable) { stopVideo().payload ?: throw IllegalStateException("video stop failed") })
                    update(current.copy(status = if (current.status == BackgroundJobStatus.STOPPING) BackgroundJobStatus.COMPLETED else BackgroundJobStatus.CANCELLED, artifact = artifact))
                } catch (t: Throwable) { update(current.copy(status = BackgroundJobStatus.FAILED, error = t.message ?: t::class.simpleName)) }
            } else if (jobs[id]?.status == BackgroundJobStatus.STOPPING) {
                val stopped = if (initial.type == "audio") microphone.stop() else null
                val clip = stopped?.payload as? MicrophoneClip
                val artifact = clip?.let { artifacts.store(wav(it), "audio/wav") }
                update(jobs[id]!!.copy(status = BackgroundJobStatus.COMPLETED, artifact = artifact))
            }
        } catch (t: Throwable) {
            update(jobs[id]!!.copy(status = BackgroundJobStatus.FAILED, error = t.message ?: t::class.simpleName))
        } finally { if (initial.type == "video") mediaForeground.stop(initial.id); runners.remove(id) }
    }

    private suspend fun runVideo(job: BackgroundJob) {
        mediaForeground.start(job.id)
        val started = startVideo(VideoStartRequest(cameraId = job.config["cameraId"]?.takeIf(String::isNotBlank), maxWidth = job.config["maxWidth"]?.toIntOrNull() ?: 1280, maxHeight = job.config["maxHeight"]?.toIntOrNull() ?: 720, maxDurationMs = job.config["durationMs"]?.toLongOrNull() ?: 60_000, maxBytes = job.config["maxBytes"]?.toIntOrNull() ?: 16_000_000))
        if (!started.success) throw IllegalStateException(started.error?.name ?: "video start failed")
        delay(job.config["durationMs"]?.toLongOrNull()?.coerceIn(1, 600_000) ?: 60_000)
        update(jobs[job.id]!!.copy(artifact = storeVideo(stopVideo().payload ?: throw IllegalStateException("video stop failed"))))
    }

    private fun storeVideo(clip: VideoClip): ArtifactMetadata = try {
        require(clip.mimeType == "video/mp4") { "Unexpected video MIME type" }
        artifacts.store(clip.file.readBytes(), clip.mimeType)
    } finally { clip.file.delete() }

    private suspend fun runAudio(job: BackgroundJob) {
        val started = microphone.start(MicrophoneStartRequest(maxBytes = job.config["maxBytes"]?.toIntOrNull()?.coerceIn(8_000, 8_000_000) ?: 320_000))
        if (!started.success) throw IllegalStateException(started.error?.name ?: "microphone start failed")
        val duration = job.config["durationMs"]?.toLongOrNull()?.coerceIn(1, 3_600_000)
        if (duration != null) delay(duration) else while (true) delay(1_000)
        val stopped = microphone.stop()
        val clip = stopped.payload as? MicrophoneClip ?: throw IllegalStateException("microphone stop failed")
        val artifact = artifacts.store(wav(clip), "audio/wav")
        update(jobs[job.id]!!.copy(artifact = artifact))
    }

    private fun update(job: BackgroundJob) { jobs[job.id] = job.copy(updatedAtEpochMs = now()); stateStore.save(jobs[job.id]!!) }
    private fun now() = System.currentTimeMillis()

    private fun BackgroundJob.toPayload() = mapOf("jobId" to id, "type" to type, "status" to status.name, "createdAtEpochMs" to createdAtEpochMs, "updatedAtEpochMs" to updatedAtEpochMs, "config" to config, "progress" to progress, "artifact" to artifact?.let { mapOf("id" to it.id, "mimeType" to it.mimeType, "sizeBytes" to it.sizeBytes) }, "error" to error)

    private fun wav(clip: MicrophoneClip): ByteArray {
        val out = ByteArrayOutputStream(44 + clip.bytes.size)
        fun le(v: Int) { out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()) }
        fun leShort(v: Short) { out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v).array()) }
        out.write("RIFF".toByteArray()); le(36 + clip.bytes.size); out.write("WAVEfmt ".toByteArray()); le(16); leShort(1); leShort(clip.channelCount.toShort()); le(clip.sampleRateHz); le(clip.sampleRateHz * clip.channelCount * 2); leShort((clip.channelCount * 2).toShort()); leShort(16); out.write("data".toByteArray()); le(clip.bytes.size); out.write(clip.bytes); return out.toByteArray()
    }

    companion object { val SUPPORTED_TYPES = setOf("audio", "video", "python", "sensor_log", "bluetooth_log", "wifi_log"); private val EXCLUSIVE_TYPES = setOf("audio", "video"); private val MEDIA_TYPES = setOf("audio", "video"); private val ACTIVE = setOf(BackgroundJobStatus.STARTING, BackgroundJobStatus.RUNNING, BackgroundJobStatus.STOPPING) }
}
