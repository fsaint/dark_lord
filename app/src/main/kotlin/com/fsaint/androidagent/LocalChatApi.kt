package com.fsaint.androidagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/** Loopback-only HTTP bridge for development and device testing. It intentionally has no auth. */
class LocalChatApi(
    private val scope: CoroutineScope,
    private val port: Int = DEFAULT_PORT,
    private val listenAddress: InetAddress? = null,
    private val handler: suspend (String) -> LocalChatReply,
) {
    @Volatile private var server: ServerSocket? = null
    private var job: Job? = null

    val isRunning: Boolean get() = server?.isClosed == false

    @Synchronized fun start() {
        if (isRunning) return
        val socket = ServerSocket(port, 16, listenAddress ?: bindAddress())
        server = socket
        job = scope.launch(Dispatchers.IO) {
            try {
                while (isActive && !socket.isClosed) {
                    val client = socket.accept()
                    launch { serve(client) }
                }
            } catch (_: Exception) {
                // Closing the socket during stop, or a transient accept failure, must not crash the app.
            }
        }
    }

    @Synchronized fun stop() {
        server?.close()
        server = null
        job?.cancel()
        job = null
    }

    private suspend fun serve(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 30_000
            val input = client.getInputStream().buffered()
            val requestLine = input.headerLine() ?: return
            val headers = mutableMapOf<String, String>()
            var headerBytes = requestLine.length
            while (true) {
                val line = input.headerLine() ?: return
                headerBytes += line.length
                if (headerBytes > 16_384) return
                if (line.isEmpty()) break
                line.substringBefore(':', "").trim().lowercase().takeIf { it.isNotEmpty() }?.let { key ->
                    headers[key] = line.substringAfter(':').trim()
                }
            }
            if (headers.containsKey("transfer-encoding")) return
            val bodyLength = headers["content-length"]?.toIntOrNull() ?: 0
            if (bodyLength !in 0..MAX_BODY_BYTES) return
            val bytes = ByteArray(bodyLength)
            var received = 0
            while (received < bodyLength) {
                val count = input.read(bytes, received, bodyLength - received)
                if (count < 0) return
                received += count
            }
            val body = bytes.toString(Charsets.UTF_8)
            val response = when {
                requestLine.startsWith("GET /health ") -> json(200, "{\"ok\":true,\"running\":true}")
                requestLine.startsWith("POST /chat ") -> runCatching { json(200, withTimeout(REQUEST_TIMEOUT_MS) { handler(body) }.toJson()) }
                    .getOrElse { json(500, "{\"error\":${quote(it.message ?: "request failed")}}") }
                else -> json(404, "{\"error\":\"not found\"}")
            }
            OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8).use { writer -> writer.write(response); writer.flush() }
        }
    }

    private fun InputStream.headerLine(): String? {
        val line = StringBuilder()
        while (line.length <= 4096) {
            val next = read()
            if (next < 0) return null
            if (next == 10) return line.toString().removeSuffix("\r")
            if (next > 127) return null
            line.append(next.toChar())
        }
        return null
    }

    private fun json(status: Int, body: String): String = "HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
    private fun quote(value: String): String = buildString { append('"'); value.forEach { c -> when (c) { '\\' -> append("\\\\"); '"' -> append("\\\""); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t"); else -> append(c) } }; append('"') }

    private fun bindAddress(): InetAddress = NetworkInterface.getNetworkInterfaces()?.toList()
        ?.asSequence()
        ?.flatMap { it.inetAddresses.toList().asSequence() }
        ?.filterIsInstance<Inet4Address>()
        ?.firstOrNull { address ->
            val bytes = address.address
            bytes[0].toInt() and 0xff == 100 && (bytes[1].toInt() and 0xff) in 64..127
        }
        ?: InetAddress.getLoopbackAddress()

    companion object { const val DEFAULT_PORT = 8765; private const val MAX_BODY_BYTES = 64 * 1024; private const val REQUEST_TIMEOUT_MS = 120_000L }
}
