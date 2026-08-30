package com.fsaint.androidagent

import com.fsaint.androidagent.runtime.BootCoordinator
import com.fsaint.androidagent.runtime.RestoreOutcome
import com.fsaint.androidagent.runtime.RestoreStep

object BootRecoveryDependencies {
    @Volatile var coordinator: BootCoordinator = defaultCoordinator()

    private fun defaultCoordinator() = BootCoordinator(listOf(
        "scopes", "skills", "capabilities", "schedules", "mcp", "runtime",
    ).map { name -> RestoreStep(name) { RestoreOutcome.Restored } })
}
