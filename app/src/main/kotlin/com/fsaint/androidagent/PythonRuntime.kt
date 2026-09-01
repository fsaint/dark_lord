package com.fsaint.androidagent

import com.chaquo.python.Python
import com.fsaint.androidagent.artifacts.ArtifactMetadata
import com.fsaint.androidagent.artifacts.ArtifactStore
import com.fsaint.androidagent.model.ScopedAgentSession
import com.fsaint.androidagent.model.PrincipalRole
import com.fsaint.androidagent.model.ToolCall
import com.fsaint.androidagent.model.ToolError
import com.fsaint.androidagent.model.ToolResult
import com.fsaint.androidagent.policy.ScopedToolRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.regex.Pattern

data class PythonExecutionResult(val stdout: String, val savedScript: String? = null)

/** Owner-only embedded Python execution. Python can reach Android only through the tool bridge. */
class PythonRuntime(
    context: android.content.Context,
    private val artifacts: ArtifactStore,
    private val executeTool: suspend (ScopedAgentSession, ToolCall) -> ToolResult<Any>,
) {
    private val workspace = File(context.applicationContext.filesDir, "python-workspace").apply { mkdirs() }

    fun handlers(): Map<String, suspend (ToolCall) -> ToolResult<Any>> = mapOf(
        "python.exec" to { call -> execute(call, null) },
        "python.save" to { call ->
            val name = validName(call.arguments["name"])
                ?: return@to ToolResult(false, error = ToolError.SCOPE_DENIED)
            val source = call.arguments["code"] ?: return@to ToolResult(false, error = ToolError.NOT_FOUND)
            if (source.length > MAX_SOURCE_CHARS) return@to ToolResult(false, error = ToolError.SCOPE_DENIED)
            File(workspace, "$name.py").writeText(source)
            ToolResult(true, mapOf("name" to name), verification = com.fsaint.androidagent.model.VerificationState.VERIFIED)
        },
        "python.list" to {
            ToolResult(true, workspace.listFiles().orEmpty().filter { it.extension == "py" }.map { it.nameWithoutExtension }.sorted(), verification = com.fsaint.androidagent.model.VerificationState.VERIFIED)
        },
        "python.run" to { call ->
            val name = validName(call.arguments["name"])
                ?: return@to ToolResult(false, error = ToolError.SCOPE_DENIED)
            val file = File(workspace, "$name.py")
            if (!file.isFile) return@to ToolResult(false, error = ToolError.NOT_FOUND)
            execute(call, file.readText())
        },
        "python.delete" to { call ->
            val name = validName(call.arguments["name"])
                ?: return@to ToolResult(false, error = ToolError.SCOPE_DENIED)
            val deleted = File(workspace, "$name.py").delete()
            if (deleted) ToolResult(true, mapOf("name" to name), verification = com.fsaint.androidagent.model.VerificationState.VERIFIED)
            else ToolResult(false, error = ToolError.NOT_FOUND)
        },
    )

    private suspend fun execute(call: ToolCall, savedSource: String?): ToolResult<Any> {
        val source = savedSource ?: call.arguments["code"] ?: return ToolResult(false, error = ToolError.NOT_FOUND)
        if (source.length > MAX_SOURCE_CHARS) return ToolResult(false, error = ToolError.SCOPE_DENIED)
        return try {
            withContext(Dispatchers.Default) {
                withTimeout(MAX_EXECUTION_MILLIS) {
                    val bridge = Bridge(OWNER_SESSION)
                    val payload = Python.getInstance().getModule("dark_lord").callAttr(
                        "execute", source, call.arguments["arguments"].orEmpty(), bridge,
                    ).toString()
                    ToolResult(true, payload, verification = com.fsaint.androidagent.model.VerificationState.VERIFIED)
                }
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            ToolResult(false, error = ToolError.TIMEOUT, recoverable = true)
        } catch (_: Throwable) {
            ToolResult(false, error = ToolError.FAILED, recoverable = true)
        }
    }

    private fun validName(value: String?): String? = value?.takeIf { NAME_PATTERN.matcher(it).matches() }

    private inner class Bridge(private val session: ScopedAgentSession) {
        fun callTool(name: String, arguments: Map<String, String>): Map<String, Any?> {
            val result = kotlinx.coroutines.runBlocking { executeTool(session, ToolCall(name, arguments)) }
            return mapOf("success" to result.success, "payload" to normalize(result.payload), "error" to result.error?.name, "recoverable" to result.recoverable)
        }

        fun artifactCreate(data: ByteArray, mimeType: String): String = artifacts.store(data, mimeType).id
        fun artifactRead(id: String): ByteArray = artifacts.read(id)?.second ?: throw IllegalArgumentException("artifact not found")
        fun artifactMetadata(id: String): Map<String, Any> = artifacts.metadata(id)?.toMap() ?: throw IllegalArgumentException("artifact not found")

        private fun normalize(value: Any?): Any? = when (value) {
            is ArtifactMetadata -> value.toMap()
            is ByteArray -> mapOf("type" to "bytes", "size" to value.size)
            else -> value?.toString()
        }
        private fun ArtifactMetadata.toMap() = mapOf("id" to id, "mimeType" to mimeType, "sizeBytes" to sizeBytes, "createdAtEpochMs" to createdAtEpochMs, "expiresAtEpochMs" to expiresAtEpochMs)
    }

    private companion object {
        const val MAX_SOURCE_CHARS = 128 * 1024
        const val MAX_EXECUTION_MILLIS = 60_000L
        val NAME_PATTERN: Pattern = Pattern.compile("[A-Za-z0-9_-]{1,64}")
        val OWNER_SESSION = ScopedAgentSession("python:owner", "owner", PrincipalRole.OWNER, "owner", "PYTHON", "owner", 0)
    }
}
