package com.fsaint.androidagent

import com.fsaint.androidagent.artifacts.ArtifactStore
import com.fsaint.androidagent.model.ToolCall
import com.fsaint.androidagent.model.ToolError
import com.fsaint.androidagent.model.ToolResult
import com.fsaint.androidagent.model.VerificationState
import com.fsaint.androidagent.runtime.TelegramBotTokenProvider
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Sends an image artifact to Telegram without exposing its local path to the agent. */
class TelegramPhotoSender(
    private val tokenProvider: TelegramBotTokenProvider,
    private val artifacts: ArtifactStore,
    private val ownerChatId: () -> String?,
) {
    fun handlers(): Map<String, suspend (ToolCall) -> ToolResult<Any>> = mapOf(
        "telegram.send_photo" to { call ->
            val artifactId = call.arguments["artifactId"].orEmpty()
            val chatId = call.arguments["chatId"]?.takeIf(String::isNotBlank) ?: ownerChatId()
            if (chatId.isNullOrBlank()) return@to ToolResult(false, error = ToolError.PERMISSION_REQUIRED)
            val artifact = artifacts.read(artifactId) ?: return@to ToolResult(false, error = ToolError.NOT_FOUND)
            if (!artifact.first.mimeType.startsWith("image/")) return@to ToolResult(false, error = ToolError.SCOPE_DENIED)
            send(chatId, artifact.first.mimeType, artifact.second)
        },
    )

    private suspend fun send(chatId: String, mimeType: String, bytes: ByteArray): ToolResult<Any> = withContext(Dispatchers.IO) {
        val token = runCatching { tokenProvider.apiToken() }.getOrElse { return@withContext ToolResult(false, error = ToolError.PERMISSION_REQUIRED) }
        val boundary = "DarkLord-${UUID.randomUUID()}"
        val endpoint = URL("https://api.telegram.org/bot$token/sendPhoto")
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 30_000
            doOutput = true
            instanceFollowRedirects = false
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        try {
            val prefix = "--$boundary\r\nContent-Disposition: form-data; name=\"chat_id\"\r\n\r\n$chatId\r\n" +
                "--$boundary\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"dark-lord.jpg\"\r\n" +
                "Content-Type: $mimeType\r\n\r\n"
            connection.outputStream.use { output ->
                output.write(prefix.toByteArray())
                output.write(bytes)
                output.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val response = connection.inputStream.bufferedReader().use { it.readText().take(64 * 1024) }
            if (connection.responseCode in 200..299 && response.contains("\"ok\":true")) {
                ToolResult(true, "photo sent", verification = VerificationState.VERIFIED)
            } else ToolResult(false, error = ToolError.NETWORK_ERROR, recoverable = true)
        } catch (_: Exception) {
            ToolResult(false, error = ToolError.NETWORK_ERROR, recoverable = true)
        } finally { connection.disconnect() }
    }
}
