package com.fsaint.androidagent

import android.util.Log
import com.fsaint.androidagent.runtime.TelegramHttpRequest
import com.fsaint.androidagent.runtime.TelegramHttpResponse
import com.fsaint.androidagent.runtime.TelegramHttpTransport
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Android HTTPS transport for the Telegram client. It logs status only; URLs contain secrets. */
class UrlConnectionTelegramTransport(
    private val maxResponseBytes: Int = 256 * 1024,
) : TelegramHttpTransport {
    override suspend fun execute(request: TelegramHttpRequest): TelegramHttpResponse = withContext(Dispatchers.IO) {
        require(request.url.startsWith("https://")) { "Telegram transport requires HTTPS" }
        require(request.body.toByteArray(Charsets.UTF_8).size <= MAX_REQUEST_BYTES) { "Telegram request too large" }
        val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = request.timeoutMillis.coerceIn(1_000L, 180_000L).toInt()
            readTimeout = request.timeoutMillis.coerceIn(1_000L, 180_000L).toInt()
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setFixedLengthStreamingMode(request.body.toByteArray(Charsets.UTF_8).size)
        }
        try {
            connection.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { readBounded(it, maxResponseBytes) }.orEmpty()
            Log.i(TAG, "Bot API HTTP status=$status")
            TelegramHttpResponse(status, body)
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(stream: java.io.InputStream, limit: Int): String {
        require(limit in 1..MAX_RESPONSE_BYTES) { "Invalid Telegram response limit" }
        val output = java.io.ByteArrayOutputStream(minOf(limit, 8 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw IllegalArgumentException("Telegram response too large")
            output.write(buffer, 0, count)
        }
        return output.toByteArray().toString(Charsets.UTF_8)
    }

    private companion object {
        private const val TAG = "DarkLordTelegram"
        private const val MAX_REQUEST_BYTES = 256 * 1024
        private const val MAX_RESPONSE_BYTES = 512 * 1024
    }
}
