package com.fsaint.androidagent.oem.samsungflip3

import android.content.Context
import android.content.Intent
import android.app.Presentation
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import androidx.compose.ui.platform.ComposeView

class AgentVoiceInteractionSession(
    context: Context,
    private val controller: CoverDisplayController,
) : VoiceInteractionSession(context) {
    private var coverPresentation: Presentation? = null

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
            setContentView(ComposeView(context).apply { setContent { AgentSurfaceRegistry.openContent() } })
        }
    }

    override fun onHide() {
        context.stopService(Intent().setClassName(context.packageName, "com.fsaint.androidagent.voice.VoiceCaptureService"))
        coverPresentation?.dismiss()
        coverPresentation = null
        super.onHide()
    }

    private fun showCover(displayId: Int) {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(displayId) ?: return
        coverPresentation?.dismiss()
        coverPresentation = Presentation(context, display).apply {
            setContentView(ComposeView(context).apply { setContent { AgentSurfaceRegistry.coverContent() } })
            show()
        }
    }
}
