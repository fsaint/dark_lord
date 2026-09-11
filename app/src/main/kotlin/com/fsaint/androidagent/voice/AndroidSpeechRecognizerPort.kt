package com.fsaint.androidagent.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Callbacks carry no partial transcript to the agent. */
interface RecognizerEvents {
    fun ready() = Unit
    fun speechStarted() = Unit
    fun partialSpeech() = Unit
    fun endOfSpeech()
    fun transcript(text: String)
    fun fail(reason: VoiceTurnError)
}

/** Platform operations are serialized on main; healthy bindings survive consecutive turns. */
class AndroidSpeechRecognizerPort(
    context: Context,
    private val events: () -> RecognizerEvents,
) : RecognizerPort {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val commands = RecognizerCommandGate { onMain(it) }
    private var attempt = 0L
    private val driver = ScheduledSpeechRecognizer(
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        available = { SpeechRecognizer.isRecognitionAvailable(appContext) },
        diagnostic = { Log.i(TAG, it) },
    ) { AndroidEngine() }

    override fun startListening() {
        // Snapshot the turn before posting, not after another press can change it.
        val callbacks = events()
        commands.submit { driver.start(callbacks) }
    }

    override fun stopListening() = commands.submit { driver.stop() }

    override fun cancel() = commands.submit { driver.cancel() }

    // The foreground capture service stops after every response. Give a healthy, inactive
    // binding an idle grace period so consecutive button presses don't unbind/rebind.
    override fun shutdown() = commands.submit { driver.shutdown() }

    private inner class AndroidEngine : SpeechEngine {
        private val platform = SpeechRecognizer.createSpeechRecognizer(appContext)
        init { Log.i(TAG, "recognizer created") }
        override fun start(events: RecognizerEvents) {
            val id = ++attempt
            val started = SystemClock.elapsedRealtime()
            fun log(event: String) = Log.i(TAG, "attempt=$id elapsedMs=${SystemClock.elapsedRealtime() - started} $event")
            platform.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { log("ready"); events.ready() }
                override fun onBeginningOfSpeech() { log("speech started"); events.speechStarted() }
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onPartialResults(partialResults: Bundle?) {
                    if (partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.any { it.isNotBlank() } == true) events.partialSpeech()
                }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onEndOfSpeech() { log("end of speech"); events.endOfSpeech() }
                override fun onResults(results: Bundle?) {
                    val candidates = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    log("results count=${candidates?.size ?: 0}")
                    events.transcript(candidates?.firstOrNull().orEmpty())
                }
                override fun onError(error: Int) {
                    log("error code=$error")
                    events.fail(when (error) {
                        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> VoiceTurnError.DISCONNECTED
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceTurnError.NO_SPEECH
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> VoiceTurnError.NETWORK
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> VoiceTurnError.BUSY
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceTurnError.MICROPHONE_PERMISSION
                        else -> VoiceTurnError.RECOGNIZER
                    })
                }
            })
            log("start")
            platform.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            })
        }
        override fun stop() { Log.i(TAG, "recognizer stop"); platform.stopListening() }
        override fun cancel() { Log.i(TAG, "recognizer cancel"); platform.cancel() }
        override fun destroy() { Log.i(TAG, "recognizer destroy"); platform.destroy() }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private companion object { const val TAG = "DarkLordVoice" }
}
