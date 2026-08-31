package com.fsaint.androidagent

import com.fsaint.androidagent.model.ToolCall
import com.fsaint.androidagent.model.ToolError
import com.fsaint.androidagent.model.ToolResult
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.ArrayDeque

/** Bounded, read-only browser operations for the agent. */
class BrowserTools(private val maxBytes: Int = 512 * 1024) {
    private val history = ArrayDeque<Page>()
    private var current: Page? = null

    @Synchronized
    fun open(call: ToolCall): ToolResult<Any> {
        val raw = call.arguments["url"].orEmpty()
        val uri = runCatching { URI(raw) }.getOrNull()
            ?: return ToolResult(false, error = ToolError.NOT_FOUND)
        if (uri.scheme != "https" || uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null) {
            return ToolResult(false, error = ToolError.SCOPE_DENIED)
        }
        val connection = runCatching { (URL(raw).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = false
        } }.getOrElse { return ToolResult(false, error = ToolError.NETWORK_ERROR, recoverable = true) }
        return try {
            val status = connection.responseCode
            if (status !in 200..299) return ToolResult(false, error = ToolError.NETWORK_ERROR, recoverable = true)
            val text = connection.inputStream.bufferedReader().use { reader ->
                val chars = CharArray(8192)
                val out = StringBuilder()
                while (out.length < maxBytes) {
                    val count = reader.read(chars)
                    if (count < 0) break
                    out.append(chars, 0, count)
                }
                out.toString().take(maxBytes)
            }
            current?.let(history::addLast)
            val page = Page(raw, htmlToText(text), extractLinks(text))
            current = page
            ToolResult(true, page, verification = com.fsaint.androidagent.model.VerificationState.VERIFIED)
        } catch (_: Exception) {
            ToolResult(false, error = ToolError.NETWORK_ERROR, recoverable = true)
        } finally { connection.disconnect() }
    }

    @Synchronized fun read(): ToolResult<Any> = current?.let { ToolResult(true, it) }
        ?: ToolResult(false, error = ToolError.NOT_FOUND)

    @Synchronized fun back(): ToolResult<Any> {
        if (history.isEmpty()) return ToolResult(false, error = ToolError.NOT_FOUND)
        val page = history.removeLast().also { current = it }
        return ToolResult(true, page)
    }

    fun handlers(): Map<String, suspend (ToolCall) -> ToolResult<Any>> = mapOf(
        "browser.open" to { call -> open(call) },
        "browser.read" to { read() },
        "browser.back" to { back() },
    )

    private fun htmlToText(html: String): String = html
        .replace(Regex("(?is)<script.*?</script>|<style.*?</style>"), " ")
        .replace(Regex("(?is)<[^>]+>"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun extractLinks(html: String): List<String> = Regex("(?i)href=[\\\"'](https://[^\\\"']+)").findAll(html)
        .map { it.groupValues[1] }.distinct().take(50).toList()

    data class Page(val url: String, val text: String, val links: List<String>)
}
