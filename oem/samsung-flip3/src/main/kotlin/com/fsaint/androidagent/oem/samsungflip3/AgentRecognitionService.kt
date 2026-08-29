package com.fsaint.androidagent.oem.samsungflip3

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Advertises the recognition endpoint required by a VoiceInteractionService. Voice capture for
 * the Stage 5 session is performed by the foreground app service; external callers receive an
 * explicit error instead of a fabricated transcript.
 */
class AgentRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, callback: Callback) {
        callback.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onStopListening(callback: Callback) = Unit

    override fun onCancel(callback: Callback) = Unit
}
