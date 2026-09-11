package com.fsaint.androidagent.runtime

import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Coordinates durable user turns without conflating history with the per-request tool loop. */
class ChatTurnCoordinator(
    private val store: ChatStore,
    private val readImage: suspend (String) -> ConversationImage?,
    private val retain: suspend (Pair<String, String>) -> Unit,
    private val release: suspend (Pair<String, String>) -> Unit,
    private val runModel: suspend (ConversationRequest) -> ConversationResult,
) {
    suspend fun run(request: ConversationRequest, chatId: String, artifactId: String? = null, selectedArtifactId: String? = null, preparedPhoto: Boolean = false, current: () -> Boolean = { true }): String {
        val ownerId = request.session.principalId
        val id = request.event.id
        fun checkCurrent() { if (!current()) throw CancellationException("Interaction superseded") }
        checkCurrent()
        val previous = store.messages(ownerId, chatId)
        store.request(ownerId, id)?.let {
            require(it.chatId == chatId) { "Request belongs to another chat" }
            if (!preparedPhoto) return previous.firstOrNull { message -> message.requestId == id && message.role == "assistant" }?.text ?: "That request has already been handled."
        }
        // Validate selection before adding input. Newly captured images are attached by trusted app code.
        ChatContext.select(previous, selectedArtifactId)
        val began = if (preparedPhoto) {
            val prepared = requireNotNull(store.request(ownerId, id))
            require(prepared.source == "CAPTURE" && prepared.state == "RUNNING" && artifactId != null && previous.any { it.requestId == id && it.artifactId == artifactId })
            true
        } else withContext(NonCancellable) {
            checkCurrent()
            artifactId?.let { retain(it to id) }
            try { store.begin(ownerId, chatId, id, request.userText, request.session.channel, artifactId,
                supersede = request.session.channel in setOf("VOICE", "CAPTURE"), current = current) }
            catch (error: Exception) { artifactId?.let { release(it to id) }; throw error }
        }
        if (!began) {
            val saved = store.messages(ownerId, chatId)
            if (artifactId != null && saved.none { it.requestId == id && it.artifactId == artifactId }) withContext(NonCancellable) { release(artifactId to id) }
            return saved.firstOrNull { it.requestId == id && it.role == "assistant" }?.text ?: "That request has already been handled."
        }
        try {
            currentCoroutineContext().ensureActive()
            checkCurrent()
            // Admission excludes concurrent requests. Reload now so a completion racing admission is included.
            val preceding = store.messages(ownerId, chatId).filter { it.requestId != id }
            val context = ChatContext.select(preceding + listOfNotNull(artifactId?.let { ChatMessage("current", id, Long.MAX_VALUE, "attachment", "", it) }), selectedArtifactId)
            var imageBytes = 0L
            val notices = mutableListOf<String>()
            if (context.omitted) notices += "Some older messages or photos are omitted from this bounded context."
            val images = context.artifactIds.mapNotNull { imageId ->
                val image = readImage(imageId)
                if (image == null || imageBytes + image.bytes.size > 8_000_000) {
                    notices += "Photo $imageId is unavailable in this request. Do not claim to see it."
                    null
                } else {
                    imageBytes += image.bytes.size
                    val label = when (imageId) {
                        artifactId -> "New photo captured for the current request: $imageId"
                        selectedArtifactId -> "Previously captured photo explicitly selected for reference: $imageId"
                        else -> "Previously captured chat photo for reference when relevant: $imageId"
                    }
                    imageId to image.copy(label = label)
                }
            }.toMap()
            val result = withTimeout(120_000) {
                runModel(request.copy(
                    transcript = ConversationTranscript(), image = artifactId?.let { images[it] },
                    priorMessages = context.messages, chatImages = images.filterKeys { it != artifactId }.values.toList(), historyNotice = notices.joinToString("\n"),
                    onToolResult = { index, call, result -> withContext(NonCancellable) {
                        store.recordTool(ownerId, id, index.toString(), buildJsonObject {
                            put("execution", "$id:$index".take(160)); put("tool", call.name.take(128))
                            put("success", result.success); put("verification", result.verification.name)
                            put("error", result.error?.name); put("result", result.payload?.toString()?.take(1024))
                            put("arguments", call.arguments.toString().take(512))
                        }.toString())
                    } },
                ))
            }
            currentCoroutineContext().ensureActive()
            checkCurrent()
            val text = result.response?.takeIf { it.isNotBlank() } ?: error("Empty model answer")
            withContext(NonCancellable) { store.finish(ownerId, id, text, "COMPLETE") }
            return text
        } catch (error: Exception) {
            val state = if (error is CancellationException && error !is TimeoutCancellationException) "INTERRUPTED" else "FAILED"
            withContext(NonCancellable) {
                store.finish(ownerId, id, if (state == "FAILED") "Could not finish this request. Check connectivity and try again; any saved photo is still in this chat." else "", state)
            }
            throw error
        }
    }
}
