package com.fsaint.androidagent

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Credential-protected latest result. Reading a notification never starts another capture. */
class PhotoConversationStore(context: Context) {
    private val preferences = context.getSharedPreferences("photo_conversation", Context.MODE_PRIVATE)
    private val current = MutableStateFlow(read())
    val state = current.asStateFlow()

    fun publish(value: PhotoConversationState) {
        preferences.edit().putString("phase", value.phase.name).putString("text", value.text)
            .putString("chatId", value.chatId).putString("requestId", value.requestId).commit()
        current.value = value
    }

    private fun read(): PhotoConversationState? {
        val phase = preferences.getString("phase", null)?.let { name -> PhotoPhase.entries.find { it.name == name } }
            ?: return null
        if (phase == PhotoPhase.CAPTURING || phase == PhotoPhase.PROCESSING) {
            return PhotoConversationState(PhotoPhase.ERROR, "The previous photo conversation was interrupted. Please try again.")
        }
        return PhotoConversationState(phase, preferences.getString("text", "").orEmpty(), preferences.getString("chatId", null), preferences.getString("requestId", null))
    }
}
