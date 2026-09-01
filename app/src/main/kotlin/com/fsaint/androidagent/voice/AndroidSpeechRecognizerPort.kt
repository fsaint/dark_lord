package com.fsaint.androidagent.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/** Recognizer callbacks the turn controller consumes. */
interface RecognizerEvents {
    fun endOfSpeech()
    fun transcript(text: String)
    fun fail(reason: VoiceTurnError)
}

/**
 * [RecognizerPort] over the platform [SpeechRecognizer]. All recognizer calls are marshalled to the main
 * looper, which the platform requires. Logs carry lifecycle and counts only, never transcript text.
 */
class AndroidSpeechRecognizerPort(
    context: Context,
    private val events: () -> RecognizerEvents,
) : RecognizerPort, RecognitionListener {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    override fun startListening() = onMain {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            Log.w(TAG, "speech recognition unavailable")
            events().fail(VoiceTurnError.RECOGNIZER)
            return@onMain
        }
        val current = recognizer ?: SpeechRecognizer.createSpeechRecognizer(appContext).also {
            it.setRecognitionListener(this)
            recognizer = it
        }
        current.startListening(recognitionIntent())
    }

    override fun stopListening() = onMain { recognizer?.stopListening() }

    override fun cancel() = onMain { recognizer?.cancel() }

    fun destroy() = onMain {
        recognizer?.destroy()
        recognizer = null
    }

    override fun onReadyForSpeech(params: Bundle?) { Log.i(TAG, "onReadyForSpeech") }
    override fun onBeginningOfSpeech() { Log.i(TAG, "onBeginningOfSpeech") }
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onPartialResults(partialResults: Bundle?) = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    override fun onEndOfSpeech() {
        Log.i(TAG, "onEndOfSpeech")
        events().endOfSpeech()
    }

    override fun onResults(results: Bundle?) = deliver("onResults", results)
    override fun onSegmentResults(segmentResults: Bundle) = deliver("onSegmentResults", segmentResults)

    override fun onError(error: Int) {
        Log.i(TAG, "onError code=$error")
        val reason = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceTurnError.NO_SPEECH
            else -> VoiceTurnError.RECOGNIZER
        }
        events().fail(reason)
    }

    private fun deliver(callback: String, results: Bundle?) {
        val candidates = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        Log.i(TAG, "$callback count=${candidates?.size ?: 0}")
        events().transcript(candidates?.firstOrNull().orEmpty())
    }

    private fun recognitionIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, COMPLETE_SILENCE_MILLIS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS)
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private companion object {
        const val TAG = "DarkLordVoice"
        const val COMPLETE_SILENCE_MILLIS = 1_000L
    }
}
