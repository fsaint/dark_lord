package com.fsaint.androidagent.mcp

import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import java.io.InputStream
import java.io.FilterInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Standard TLS verification, explicit endpoints only, never follow redirects or leak headers. */
class UrlConnectionMcpTransport internal constructor(private val openConnection: (URL) -> HttpURLConnection) : McpStreamTransport {
    constructor() : this({ it.openConnection() as HttpURLConnection })
    override suspend fun exchange(request: McpExchangeRequest, onMessage: suspend (JsonObject) -> Boolean): McpStreamResponse = suspendCancellableCoroutine { continuation ->
        val active = AtomicReference<HttpURLConnection?>()
        val job = workers.launch {
            try {
                val url = validateMcpEndpoint(request.url)
                val bytes = request.body.toString().toByteArray(Charsets.UTF_8)
                require(bytes.size <= 1_048_576)
                val connection = openConnection(URI(url).toURL()).apply {
                    instanceFollowRedirects = false; requestMethod = "POST"; doOutput = true
                    connectTimeout = request.timeoutMillis.toInt(); readTimeout = request.timeoutMillis.toInt()
                    request.headers.forEach { (key, value) -> setRequestProperty(key, value) }
                    setFixedLengthStreamingMode(bytes.size)
                }
                active.set(connection)
                currentCoroutineContext().ensureActive()
                connection.outputStream.use { it.write(bytes) }
                val status = connection.responseCode
                val headers = connection.headerFields.filterKeys { it != null }.mapValues { it.value.firstOrNull().orEmpty() }
                if (status in 200..299 && status != 202 && status != 204) {
                    connection.inputStream.use { McpStreamReader.read(it, connection.contentType.orEmpty(), onMessage) }
                }
                if (continuation.isActive) continuation.resume(McpStreamResponse(status, headers))
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } finally { active.getAndSet(null)?.disconnect() }
        }
        continuation.invokeOnCancellation { job.cancel(); workers.launch { active.getAndSet(null)?.disconnect() } }
    }
    private companion object { val workers = CoroutineScope(SupervisorJob() + Dispatchers.IO) }
}

object McpStreamReader {
    suspend fun read(stream: InputStream, contentType: String, onMessage: suspend (JsonObject) -> Boolean) {
        val reader = LimitedInput(stream).bufferedReader(Charsets.UTF_8)
        when (contentType.substringBefore(';').trim().lowercase()) {
            "application/json" -> onMessage(parseMcpObject(reader.readText()))
            "text/event-stream" -> {
                val data = StringBuilder()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) {
                        if (data.isNotBlank() && onMessage(parseMcpObject(data.toString()))) return
                        data.setLength(0)
                    } else if (line.startsWith("data:")) {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.substring(5).removePrefix(" "))
                    }
                }
                // An incomplete SSE event is not a response. Caller reports unknown outcome.
            }
            else -> throw McpFailure("UNSUPPORTED", "MCP endpoint did not return JSON or an event stream.")
        }
    }
    private class LimitedInput(stream: InputStream) : FilterInputStream(stream) {
        private var bytes = 0
        private fun count(n: Int): Int { if (n > 0) { bytes += n; if (bytes > 1_048_576) throw McpFailure("UNSUPPORTED", "MCP response exceeds the size limit.") }; return n }
        override fun read(): Int = super.read().also { if (it >= 0) count(1) }
        override fun read(buffer: ByteArray, off: Int, len: Int): Int = count(`in`.read(buffer, off, len.coerceAtMost(1_048_577 - bytes)))
    }
}
