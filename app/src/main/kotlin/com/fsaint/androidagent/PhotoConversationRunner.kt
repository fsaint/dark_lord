package com.fsaint.androidagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

enum class PhotoPhase { CAPTURING, PROCESSING, COMPLETE, ERROR }
data class PhotoConversationState(val phase: PhotoPhase, val text: String, val chatId: String? = null, val requestId: String? = null) {
    val terminal: Boolean get() = phase == PhotoPhase.COMPLETE || phase == PhotoPhase.ERROR
}

internal class PhotoConversationRunner<T>(
    private val capture: suspend () -> T,
    private val analyze: suspend (T) -> String,
    private val publish: (PhotoConversationState) -> Unit,
    private val speak: suspend (String) -> Unit,
    private val timeoutMillis: Long = 120_000,
) {
    suspend fun run() {
        val answer = try {
            withTimeout(timeoutMillis) {
                publish(PhotoConversationState(PhotoPhase.CAPTURING, "Taking a picture…"))
                val image = capture()
                publish(PhotoConversationState(PhotoPhase.PROCESSING, "Picture captured. Asking Dark Lord…"))
                analyze(image).takeIf { it.isNotBlank() }
                    ?: throw PhotoConversationFailure("The model did not return an answer. Please try again.")
            }
        } catch (_: TimeoutCancellationException) {
            publish(PhotoConversationState(PhotoPhase.ERROR, "The photo conversation timed out. Please try again."))
            return
        } catch (cancelled: CancellationException) {
            publish(PhotoConversationState(PhotoPhase.ERROR, "Photo conversation interrupted. Please try again."))
            throw cancelled
        } catch (error: Exception) {
            // Never expose arbitrary exception messages (which may include credentials) in speech/notifications.
            publish(PhotoConversationState(PhotoPhase.ERROR, (error as? PhotoConversationFailure)?.message
                ?: "Could not complete the photo conversation. Check connectivity and try again."))
            return
        }
        publish(PhotoConversationState(PhotoPhase.COMPLETE, answer))
        try {
            speak(answer)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The durable answer remains available if speech is unavailable.
        }
    }
}

/** Only app-authored, non-sensitive messages belong in this exception. */
internal class PhotoConversationFailure(message: String) : Exception(message)
