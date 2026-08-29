package com.fsaint.androidagent.oem.samsungflip3

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import java.util.UUID

class AgentVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        val displays = AndroidDisplayProvider(this)
        return AgentVoiceInteractionSession(
            context = this,
            controller = CoverDisplayController(
                displayProvider = displays,
                postureProvider = DisplayBackedPostureProvider(displays),
                taskId = args?.getString("task_id") ?: UUID.randomUUID().toString(),
            ),
        )
    }
}
