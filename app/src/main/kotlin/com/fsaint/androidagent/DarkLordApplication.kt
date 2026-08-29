package com.fsaint.androidagent

import android.app.Application
import com.fsaint.androidagent.oem.samsungflip3.AgentSurfaceRegistry
import com.fsaint.androidagent.ui.CoverAssistantScreen
import com.fsaint.androidagent.ui.OpenAssistantScreen

class DarkLordApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AgentSurfaceRegistry.openContent = {
            OpenAssistantScreen(
                onRequestAssistantRole = {},
                onRequestCapabilityPermissions = {},
            )
        }
        AgentSurfaceRegistry.coverContent = { CoverAssistantScreen() }
    }
}
