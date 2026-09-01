package com.fsaint.androidagent.voice

import com.fsaint.androidagent.runtime.ReplySender

/** Routes `VOICE` replies to the push-to-talk turn; every other channel goes to [fallback] unchanged. */
class VoiceResponder(
    private val controller: PushToTalkController,
    private val fallback: ReplySender,
) : ReplySender {
    override suspend fun send(channel: String, recipient: String, text: String) {
        if (channel.equals(VOICE_CHANNEL, ignoreCase = true)) {
            if (text.isNotBlank()) controller.replyReady(text)
        } else {
            fallback.send(channel, recipient, text)
        }
    }

    private companion object {
        const val VOICE_CHANNEL = "VOICE"
    }
}
