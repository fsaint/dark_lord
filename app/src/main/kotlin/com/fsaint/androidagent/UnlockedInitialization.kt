package com.fsaint.androidagent

internal class UnlockedInitialization {
    private var initialized = false

    @Synchronized
    fun initialize(unlocked: Boolean, initialize: () -> Unit) {
        if (!unlocked || initialized) return
        initialize()
        initialized = true
    }
}
