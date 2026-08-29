package com.fsaint.androidagent.oem.samsungflip3

import androidx.compose.runtime.Composable

/** App-owned Compose content installed before the OS creates an Assistant session. */
object AgentSurfaceRegistry {
    var openContent: @Composable () -> Unit = {}
    var coverContent: @Composable () -> Unit = {}
}
