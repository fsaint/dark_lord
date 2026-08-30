package com.fsaint.androidagent

import com.fsaint.androidagent.runtime.OpenAiHttpRequest
import com.fsaint.androidagent.runtime.OpenAiHttpResponse
import com.fsaint.androidagent.runtime.OpenAiHttpTransport
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Small Android transport seam; the runtime remains platform-independent and testable. */
class UrlConnectionOpenAiTransport : OpenAiHttpTransport {
    override suspend fun execute(request: OpenAiHttpRequest): OpenAiHttpResponse = withContext(Dispatchers.IO) {
        val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = request.timeoutMillis.toInt()
            readTimeout = request.timeoutMillis.toInt()
            doOutput = true
            setRequestProperty("Authorization", request.authorization)
            setRequestProperty("Content-Type", "application/json")
            setFixedLengthStreamingMode(request.body.toByteArray().size)
        }
        try {
            connection.outputStream.use { it.write(request.body.toByteArray()) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val status = connection.responseCode
            Log.i("DarkLordOpenAI", "Responses API HTTP status=$status")
            OpenAiHttpResponse(status, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }
}
