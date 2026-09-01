package com.fsaint.androidagent.oem.samsungflip3

import android.content.Context
import android.content.Intent
import android.app.Presentation
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.SystemClock
import android.service.voice.VoiceInteractionSession
import android.util.Log
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

class AgentVoiceInteractionSession(
    context: Context,
    private val controller: CoverDisplayController,
) : VoiceInteractionSession(context) {
    private var coverPresentation: Presentation? = null
    private var composeOwner: SessionComposeOwner? = null
    private var shownAtElapsedMs = 0L

    var currentPresentation: AssistantPresentation? = null
        private set

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        shownAtElapsedMs = SystemClock.elapsedRealtime()
        Log.i(
            TAG,
            "onShow flags=0x${showFlags.toString(16)} pushToTalk=${showFlags and SHOW_SOURCE_PUSH_TO_TALK != 0}" +
                " argKeys=${args?.keySet()?.joinToString()} invocationType=${args?.getInt("invocation_type", -1)}" +
                " extraTime=${args?.getLong(Intent.EXTRA_TIME, -1L)} elapsed=$shownAtElapsedMs",
        )
        context.startForegroundService(
            Intent().setClassName(context.packageName, VOICE_CAPTURE_SERVICE).setAction(ACTION_PRESS),
        )
        val presentation = controller.presentation()
        currentPresentation = presentation
        if (presentation.renderer == AssistantRenderer.COVER) {
            showCover(presentation.display!!.id)
        } else {
            coverPresentation?.dismiss()
            coverPresentation = null
            setContentView(composeView { AgentSurfaceRegistry.openContent() })
        }
    }

    override fun onHide() {
        Log.i(TAG, "onHide sinceShowMs=${SystemClock.elapsedRealtime() - shownAtElapsedMs}")
        // The hide is the closest signal to a key release; capture keeps running until the turn ends.
        runCatching {
            context.startService(Intent().setClassName(context.packageName, VOICE_CAPTURE_SERVICE).setAction(ACTION_RELEASE))
        }.onFailure { Log.w(TAG, "release not delivered: ${it::class.simpleName}") }
        coverPresentation?.dismiss()
        coverPresentation = null
        composeOwner?.destroy()
        composeOwner = null
        super.onHide()
    }

    private fun showCover(displayId: Int) {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(displayId) ?: return
        coverPresentation?.dismiss()
        coverPresentation = Presentation(context, display).apply {
            setContentView(composeView { AgentSurfaceRegistry.coverContent() })
            show()
        }
    }

    private fun composeView(content: @androidx.compose.runtime.Composable () -> Unit): ComposeView {
        composeOwner?.destroy()
        return ComposeView(context).also { view ->
            SessionComposeOwner().also { owner ->
                owner.attachTo(view)
                composeOwner = owner
            }
            view.setContent(content)
        }
    }
}

private const val TAG = "DarkLordAssist"

/** Mirrors `VoiceCaptureService.Companion` in `:app`; this module cannot depend on the app module. */
private const val VOICE_CAPTURE_SERVICE = "com.fsaint.androidagent.voice.VoiceCaptureService"
private const val ACTION_PRESS = "com.fsaint.androidagent.voice.PRESS"
private const val ACTION_RELEASE = "com.fsaint.androidagent.voice.RELEASE"

/** `VoiceInteractionSession.SHOW_SOURCE_PUSH_TO_TALK`: the session was invoked from a physical button. */
private const val SHOW_SOURCE_PUSH_TO_TALK = 1 shl 5

/** Supplies the view-tree owners normally installed by a ComponentActivity. */
private class SessionComposeOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    init {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun attachTo(view: ComposeView) {
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
    }

    fun destroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }
}
