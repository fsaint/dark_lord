package com.fsaint.androidagent.oem.samsungflip3

import android.content.Context
import android.content.Intent
import android.app.Presentation
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
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

    var currentPresentation: AssistantPresentation? = null
        private set

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        context.startForegroundService(
            Intent().setClassName(context.packageName, "com.fsaint.androidagent.voice.VoiceCaptureService"),
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
        context.stopService(Intent().setClassName(context.packageName, "com.fsaint.androidagent.voice.VoiceCaptureService"))
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
