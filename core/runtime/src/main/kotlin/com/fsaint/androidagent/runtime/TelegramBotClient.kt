package com.fsaint.androidagent.runtime

import java.nio.charset.StandardCharsets
import java.net.URI

/** A request passed to the Android (or test) HTTP implementation. */
class TelegramHttpRequest(
    val url: String,
    val body: String,
    val timeoutMillis: Long,
) {
    /** Do not allow accidental logging of the bot token embedded in the URL. */
    override fun toString(): String = "TelegramHttpRequest(url=<redacted>, bodyBytes=${body.toByteArray(StandardCharsets.UTF_8).size}, timeoutMillis=$timeoutMillis)"
}

data class TelegramHttpResponse(val status: Int, val body: String)

interface TelegramHttpTransport {
    suspend fun execute(request: TelegramHttpRequest): TelegramHttpResponse
}

interface TelegramResult {
    data class Success(val messageId: Long? = null) : TelegramResult
    data class Failure(val errorCode: Int? = null, val description: String = "Telegram request failed") : TelegramResult
}

/** A Telegram update. Non-text updates retain their IDs so callers can durably acknowledge them. */
data class TelegramUpdate(
    val updateId: Long,
    val chatId: String? = null,
    val text: String? = null,
)

/**
 * Small, bounded Telegram Bot API client. The bot token is obtained only at request time and is
 * never included in exceptions or the request's diagnostic string.
 */
class TelegramBotClient(
    private val transport: TelegramHttpTransport,
    private val tokenProvider: TelegramBotTokenProvider,
    private val apiBaseUrl: String = "https://api.telegram.org",
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val maxBodyBytes: Int = MAX_BODY_BYTES,
) : TelegramMessagingClient {
    override suspend fun sendMessage(chatId: String, text: String): TelegramResult {
        val normalizedBase = normalizedBaseUrl() ?: return TelegramResult.Failure(description = "HTTPS is required")
        if (chatId.isBlank() || chatId.length > MAX_CHAT_ID_LENGTH || text.isEmpty() || text.length > MAX_TEXT_LENGTH) {
            return TelegramResult.Failure(description = "Telegram payload exceeds limits")
        }
        val body = "{\"chat_id\":\"${escape(chatId)}\",\"text\":\"${escape(text)}\"}"
        return execute(normalizedBase, "sendMessage", body, timeoutMillis.coerceIn(MIN_TIMEOUT_MILLIS, MAX_TIMEOUT_MILLIS))
            .toSendResult()
    }

    override suspend fun getUpdates(offset: Long?, timeoutSeconds: Int): List<TelegramUpdate> {
        val normalizedBase = normalizedBaseUrl() ?: return emptyList()
        val normalizedTimeout = timeoutSeconds.coerceIn(0, MAX_POLL_SECONDS)
        val body = buildString {
            append('{')
            if (offset != null) append("\"offset\":$offset,")
            append("\"timeout\":$normalizedTimeout}")
        }
        val requestTimeout = (timeoutMillis.coerceIn(MIN_TIMEOUT_MILLIS, MAX_TIMEOUT_MILLIS) + normalizedTimeout * 1_000L)
            .coerceAtMost(MAX_LONG_POLL_TIMEOUT_MILLIS)
        val response = execute(normalizedBase, "getUpdates", body, requestTimeout)
        return response.resultAsList()
    }

    private suspend fun execute(baseUrl: String, method: String, body: String, requestTimeout: Long): ParsedTelegramResponse {
        if (body.toByteArray(StandardCharsets.UTF_8).size > maxBodyBytes) {
            return ParsedTelegramResponse(false, errorCode = null, description = "Telegram payload exceeds limits")
        }
        val token = runCatching { tokenProvider.apiToken() }.getOrNull()
            ?.takeIf { TOKEN_PATTERN.matches(it) }
            ?: return ParsedTelegramResponse(false, errorCode = null, description = "Telegram bot token is not configured")
        val url = "$baseUrl/bot$token/$method"
        val response = runCatching { transport.execute(TelegramHttpRequest(url, body, requestTimeout)) }
            .getOrElse { return ParsedTelegramResponse(false, description = "Telegram transport failed") }
        if (response.body.toByteArray(StandardCharsets.UTF_8).size > maxBodyBytes) {
            return ParsedTelegramResponse(false, description = "Telegram response exceeds limits")
        }
        val parsed = runCatching { TelegramJson.parse(response.body) as? Map<*, *> }.getOrNull()
            ?: return ParsedTelegramResponse(false, errorCode = response.status, description = "Invalid Telegram response")
        val ok = parsed["ok"] as? Boolean ?: false
        val errorCode = (parsed["error_code"] as? Number)?.toInt() ?: response.status.takeIf { it !in 200..299 }
        val description = parsed["description"] as? String
        return ParsedTelegramResponse(ok && response.status in 200..299, errorCode, description, parsed["result"])
    }

    private fun ParsedTelegramResponse.toSendResult(): TelegramResult =
        if (ok) TelegramResult.Success(((result as? Map<*, *>)?.get("message_id") as? Number)?.toLong())
        else TelegramResult.Failure(errorCode, description ?: "Telegram request failed")

    private fun ParsedTelegramResponse.resultAsList(): List<TelegramUpdate> {
        if (!ok) return emptyList()
        val updates = result as? List<*> ?: return emptyList()
        return updates.mapNotNull { item ->
            val update = item as? Map<*, *> ?: return@mapNotNull null
            val updateId = (update["update_id"] as? Number)?.toLong() ?: return@mapNotNull null
            val message = update["message"] as? Map<*, *>
            val chat = message?.get("chat") as? Map<*, *>
            val chatId = chat?.get("id")?.toString()?.takeIf { it.isNotBlank() }
            val text = message?.get("text") as? String
            TelegramUpdate(updateId, chatId, text)
        }
    }

    private fun normalizedBaseUrl(): String? {
        val uri = runCatching { URI(apiBaseUrl) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host != TELEGRAM_HOST ||
            (uri.port != -1 && uri.port != 443) || uri.userInfo != null ||
            uri.rawPath !in listOf("", "/") || uri.rawQuery != null || uri.rawFragment != null
        ) return null
        // Always construct requests from this canonical origin; never preserve attacker-controlled
        // casing, path, query, fragment, or authority components from the configured value.
        return "https://$TELEGRAM_HOST"
    }

    private fun escape(value: String): String = buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) append(" ") else append(character)
            }
        }
    }

    private data class ParsedTelegramResponse(
        val ok: Boolean,
        val errorCode: Int? = null,
        val description: String? = null,
        val result: Any? = null,
    )

    private companion object {
        private const val DEFAULT_TIMEOUT_MILLIS = 30_000L
        private const val MIN_TIMEOUT_MILLIS = 1_000L
        private const val MAX_TIMEOUT_MILLIS = 120_000L
        private const val MAX_LONG_POLL_TIMEOUT_MILLIS = 180_000L
        private const val MAX_POLL_SECONDS = 50
        private const val MAX_BODY_BYTES = 256 * 1024
        private const val MAX_TEXT_LENGTH = 4_096
        private const val MAX_CHAT_ID_LENGTH = 256
        private const val MAX_TOKEN_LENGTH = 512
        private const val TELEGRAM_HOST = "api.telegram.org"
        private val TOKEN_PATTERN = Regex("^[0-9]{1,16}:[A-Za-z0-9_-]{20,}$")
    }
}

/** Minimal JSON parser for the bounded Telegram response subset; avoids adding a runtime JSON dependency. */
private object TelegramJson {
    fun parse(input: String): Any? = Parser(input).parse()

    private class Parser(private val input: String) {
        private var index = 0

        fun parse(): Any? {
            skipWhitespace()
            val value = value()
            skipWhitespace()
            check(index == input.length)
            return value
        }

        private fun value(): Any? {
            skipWhitespace()
            check(index < input.length)
            return when (input[index]) {
                '{' -> objectValue()
                '[' -> arrayValue()
                '"' -> stringValue()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> numberValue()
            }
        }

        private fun objectValue(): Map<String, Any?> {
            index++
            val result = linkedMapOf<String, Any?>()
            skipWhitespace()
            if (consume('}')) return result
            while (true) {
                skipWhitespace()
                check(input.getOrNull(index) == '"')
                val key = stringValue()
                skipWhitespace()
                check(consume(':'))
                result[key] = value()
                skipWhitespace()
                if (consume('}')) return result
                check(consume(','))
            }
        }

        private fun arrayValue(): List<Any?> {
            index++
            val result = mutableListOf<Any?>()
            skipWhitespace()
            if (consume(']')) return result
            while (true) {
                result += value()
                skipWhitespace()
                if (consume(']')) return result
                check(consume(','))
            }
        }

        private fun stringValue(): String {
            check(consume('"'))
            val result = StringBuilder()
            while (index < input.length) {
                when (val character = input[index++]) {
                    '"' -> return result.toString()
                    '\\' -> {
                        check(index < input.length)
                        when (val escaped = input[index++]) {
                            '"', '\\', '/' -> result.append(escaped)
                            'b' -> result.append('\b')
                            'f' -> result.append('\u000c')
                            'n' -> result.append('\n')
                            'r' -> result.append('\r')
                            't' -> result.append('\t')
                            'u' -> {
                                check(index + 4 <= input.length)
                                result.append(input.substring(index, index + 4).toInt(16).toChar())
                                index += 4
                            }
                            else -> error("invalid JSON escape")
                        }
                    }
                    else -> {
                        check(character.code >= 0x20)
                        result.append(character)
                    }
                }
            }
            error("unterminated JSON string")
        }

        private fun numberValue(): Number {
            val start = index
            if (input.getOrNull(index) == '-') index++
            while (input.getOrNull(index)?.isDigit() == true) index++
            if (input.getOrNull(index) == '.') {
                index++
                while (input.getOrNull(index)?.isDigit() == true) index++
            }
            if (input.getOrNull(index) in listOf('e', 'E')) {
                index++
                if (input.getOrNull(index) in listOf('+', '-')) index++
                while (input.getOrNull(index)?.isDigit() == true) index++
            }
            val number = input.substring(start, index)
            return number.toLongOrNull() ?: number.toDoubleOrNull() ?: error("invalid JSON number")
        }

        private fun <T> literal(expected: String, result: T): T {
            check(input.regionMatches(index, expected, 0, expected.length))
            index += expected.length
            return result
        }

        private fun skipWhitespace() { while (input.getOrNull(index)?.isWhitespace() == true) index++ }
        private fun consume(character: Char): Boolean = input.getOrNull(index) == character && ++index >= 0
    }
}
