package com.fsaint.androidagent

import com.fsaint.androidagent.runtime.OpenAiHttpRequest
import com.fsaint.androidagent.runtime.OpenAiHttpResponse
import com.fsaint.androidagent.runtime.OpenAiHttpTransport
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Small Android transport seam; the runtime remains platform-independent and testable. */
class UrlConnectionOpenAiTransport : OpenAiHttpTransport {
    override suspend fun execute(request: OpenAiHttpRequest): OpenAiHttpResponse = suspendCancellableCoroutine { continuation ->
        val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = request.timeoutMillis.toInt()
            readTimeout = request.timeoutMillis.toInt()
            doOutput = true
            setRequestProperty("Authorization", request.authorization)
            setRequestProperty("Content-Type", "application/json")
            setFixedLengthStreamingMode(request.body.toByteArray().size)
        }
        // Cancellation resumes the caller immediately; even disconnect can block on some devices.
        val worker = transportIo.launch {
            try {
                ensureActive()
                connection.outputStream.use { it.write(request.body.toByteArray()) }
                val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
                val status = connection.responseCode
                Log.i("DarkLordOpenAI", "Responses API HTTP status=$status")
                val response = OpenAiHttpResponse(status, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
                if (continuation.isActive) continuation.resume(response)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } finally { connection.disconnect() }
        }
        continuation.invokeOnCancellation { worker.cancel(); transportIo.launch { connection.disconnect() } }
    }
    private companion object { val transportIo = CoroutineScope(SupervisorJob() + Dispatchers.IO) }
}
