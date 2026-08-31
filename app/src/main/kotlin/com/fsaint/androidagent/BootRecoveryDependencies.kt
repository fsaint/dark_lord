package com.fsaint.androidagent

import android.content.Context
import androidx.core.content.ContextCompat
import com.fsaint.androidagent.runtime.BootCoordinator
import com.fsaint.androidagent.runtime.RestoreOutcome
import com.fsaint.androidagent.runtime.RestoreStep

fun interface AgentRuntimeForegroundStarter {
    fun start(context: Context)
}

fun interface BackgroundRuntimeRestorer {
    suspend fun restore()
}

object BootRecoveryDependencies {
    @Volatile var coordinator: AgentRuntimeRecovery = defaultCoordinator()
    @Volatile var restorer: BackgroundRuntimeRestorer = defaultRestorer()
    @Volatile var foregroundStarter: AgentRuntimeForegroundStarter = defaultForegroundStarter()

    private fun defaultCoordinator(): AgentRuntimeRecovery {
        return object : AgentRuntimeRecovery {
            override fun start() = Unit
            override suspend fun stop() = Unit
            override val isRunning = false
            override suspend fun restore() = Unit
        }
    }

    private fun defaultRestorer(): BackgroundRuntimeRestorer {
        val boot = BootCoordinator(listOf(
            "scopes", "skills", "capabilities", "schedules", "mcp", "runtime",
        ).map { name -> RestoreStep(name) { RestoreOutcome.Restored } })
        return BackgroundRuntimeRestorer { boot.restore() }
    }

    private fun defaultForegroundStarter(): AgentRuntimeForegroundStarter =
        AgentRuntimeForegroundStarter { context ->
            ContextCompat.startForegroundService(context, AgentRuntimeService.startIntent(context))
        }
}
