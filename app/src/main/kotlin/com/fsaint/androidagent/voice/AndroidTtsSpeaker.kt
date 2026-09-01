package com.fsaint.androidagent.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * [Speaker] backed by the platform [TextToSpeech] engine.
 *
 * Every [speak] completes its callback exactly once: on utterance completion, on error, when a newer
 * utterance flushes it, or immediately when the engine failed to initialise, so the turn controller
 * never waits on a reply that will not be spoken. Speech takes transient, may-duck audio focus as an
 * assistant so music is lowered rather than stopped.
 */
class AndroidTtsSpeaker(context: Context) : Speaker {
    private val appContext = context.applicationContext
    private val audioManager: AudioManager? = appContext.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()
    private val lock = Any()
    private val counter = AtomicInteger()
    private val callbacks = ConcurrentHashMap<String, () -> Unit>()
    private var ready = false
    private var failed = false
    private var queued: Pair<String, () -> Unit>? = null
    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) = Unit
        override fun onDone(utteranceId: String) = complete(utteranceId)
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) = complete(utteranceId)
        override fun onError(utteranceId: String, errorCode: Int) {
            Log.w(TAG, "utterance error code=$errorCode")
            complete(utteranceId)
        }
        override fun onStop(utteranceId: String, interrupted: Boolean) = complete(utteranceId)
    }
    private val tts = TextToSpeech(appContext) { status -> onInit(status) }

    override fun speak(text: String, onDone: () -> Unit) {
        synchronized(lock) {
            if (failed) {
                onDone()
                return
            }
            if (!ready) {
                queued?.second?.invoke()
                queued = text to onDone
                return
            }
        }
        speakNow(text, onDone)
    }

    override fun stop() {
        synchronized(lock) { queued?.second?.also { queued = null }?.invoke() }
        if (ready) tts.stop()
    }

    override fun shutdown() {
        synchronized(lock) {
            ready = false
            failed = true
            queued?.second?.invoke()
            queued = null
        }
        abandonFocus()
        runCatching { tts.shutdown() }
        callbacks.keys.toList().forEach(::complete)
    }

    private fun onInit(status: Int) {
        val pending: Pair<String, () -> Unit>?
        synchronized(lock) {
            if (status == TextToSpeech.SUCCESS && applyLanguage()) {
                tts.setAudioAttributes(attributes)
                tts.setOnUtteranceProgressListener(listener)
                ready = true
            } else {
                Log.w(TAG, "text-to-speech unavailable status=$status")
                failed = true
            }
            pending = queued
            queued = null
        }
        pending?.let { (text, onDone) -> speak(text, onDone) }
    }

    private fun applyLanguage(): Boolean {
        val preferred = tts.setLanguage(Locale.getDefault())
        if (preferred != TextToSpeech.LANG_MISSING_DATA && preferred != TextToSpeech.LANG_NOT_SUPPORTED) return true
        val fallback = tts.setLanguage(Locale.US)
        return fallback != TextToSpeech.LANG_MISSING_DATA && fallback != TextToSpeech.LANG_NOT_SUPPORTED
    }

    private fun speakNow(text: String, onDone: () -> Unit) {
        val id = "dark-lord-${counter.incrementAndGet()}"
        callbacks[id] = onDone
        audioManager?.requestAudioFocus(focusRequest)
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (result != TextToSpeech.SUCCESS) {
            Log.w(TAG, "speak rejected result=$result")
            complete(id)
        }
    }

    private fun complete(utteranceId: String) {
        val onDone = callbacks.remove(utteranceId) ?: return
        if (callbacks.isEmpty()) abandonFocus()
        onDone()
    }

    private fun abandonFocus() {
        audioManager?.abandonAudioFocusRequest(focusRequest)
    }

    private companion object {
        const val TAG = "DarkLordVoice"
    }
}
