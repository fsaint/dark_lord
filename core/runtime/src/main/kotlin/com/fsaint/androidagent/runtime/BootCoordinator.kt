package com.fsaint.androidagent.runtime

import com.fsaint.androidagent.model.ToolError

sealed interface RestoreOutcome {
    data object Restored : RestoreOutcome
    data class Failed(val error: ToolError) : RestoreOutcome
}

class RestoreStep(val id: String, private val action: suspend () -> RestoreOutcome) {
    suspend fun run(): RestoreOutcome = action()
}

data class BootRestoreReport(val restored: List<String>, val failures: Map<String, ToolError>)

class BootCoordinator(private val steps: List<RestoreStep>) {
    private var report: BootRestoreReport? = null

    suspend fun restore(): BootRestoreReport = report ?: buildReport().also { report = it }

    private suspend fun buildReport(): BootRestoreReport {
        val restored = mutableListOf<String>()
        val failures = linkedMapOf<String, ToolError>()
        steps.forEach { step -> when (val result = step.run()) {
            RestoreOutcome.Restored -> restored += step.id
            is RestoreOutcome.Failed -> failures[step.id] = result.error
        } }
        return BootRestoreReport(restored, failures)
    }
}
