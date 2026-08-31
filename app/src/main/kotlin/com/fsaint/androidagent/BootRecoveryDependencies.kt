package com.fsaint.androidagent

import com.fsaint.androidagent.runtime.BootCoordinator
import com.fsaint.androidagent.runtime.RestoreOutcome
import com.fsaint.androidagent.runtime.RestoreStep

object BootRecoveryDependencies {
    @Volatile var coordinator: AgentRuntimeRecovery = defaultCoordinator()

    private fun defaultCoordinator(): AgentRuntimeRecovery {
        val boot = BootCoordinator(listOf(
            "scopes", "skills", "capabilities", "schedules", "mcp", "runtime",
        ).map { name -> RestoreStep(name) { RestoreOutcome.Restored } })
        return object : AgentRuntimeRecovery {
        override fun start() = Unit
        override suspend fun stop() = Unit
        override val isRunning = false
        override suspend fun restore() { boot.restore() }
        }
    }
}
