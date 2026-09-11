package com.fsaint.androidagent

import com.fsaint.androidagent.artifacts.ArtifactStore
import com.fsaint.androidagent.model.ToolCall
import com.fsaint.androidagent.model.ToolError
import com.fsaint.androidagent.runtime.TelegramBotTokenProvider
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelegramPhotoSenderTest {
    private val artifacts = ArtifactStore(Files.createTempDirectory("telegram-photo").toFile())
    private val token = object : TelegramBotTokenProvider { override suspend fun apiToken() = "123:abc" }
    private val missingToken = object : TelegramBotTokenProvider {
        override suspend fun apiToken(): String = throw IllegalStateException("no token")
    }

    @Test fun explainsMissingOwnerChatIdInsteadOfBarePermissionError() = runTest {
        artifacts.store(byteArrayOf(1), "image/jpeg")
        val send = TelegramPhotoSender(token, artifacts) { null }.handlers().getValue("telegram.send_photo")
        val result = send(ToolCall("telegram.send_photo"))
        assertEquals(ToolError.PERMISSION_REQUIRED, result.error)
        assertTrue(result.payload.toString().contains("chat id"), result.payload.toString())
    }

    @Test fun explainsMissingBotTokenInsteadOfBarePermissionError() = runTest {
        val photo = artifacts.store(byteArrayOf(1), "image/jpeg")
        val send = TelegramPhotoSender(missingToken, artifacts) { "42" }.handlers().getValue("telegram.send_photo")
        val result = send(ToolCall("telegram.send_photo", mapOf("artifactId" to photo.id)))
        assertEquals(ToolError.PERMISSION_REQUIRED, result.error)
        assertTrue(result.payload.toString().contains("bot token"), result.payload.toString())
    }
}
