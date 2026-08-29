package com.fsaint.androidagent.oem.samsungflip3

import com.fsaint.androidagent.model.AgentCapability
import com.fsaint.androidagent.model.AgentEvent
import com.fsaint.androidagent.model.AgentTool
import com.fsaint.androidagent.model.CapabilityStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

data class DisplayDescriptor(
    val id: Int,
    val width: Int,
    val height: Int,
    val isPresentation: Boolean,
)

interface DisplayProvider {
    fun presentationDisplays(): List<DisplayDescriptor>
}

enum class FlipPosture { OPEN, CLOSED }

interface PostureProvider {
    val current: FlipPosture
}

class Flip3FormFactorCapability(
    private val displayProvider: DisplayProvider,
    private val postureProvider: PostureProvider,
) : AgentCapability {
    override val id = "flip3.coverUi"
    override val version = "1.0"

    private var currentStatus = CapabilityStatus(available = false)

    override suspend fun initialize(): CapabilityStatus = refresh()

    override fun tools(): List<AgentTool> = emptyList()

    override fun events(): Flow<AgentEvent> = emptyFlow()

    override fun status(): CapabilityStatus = currentStatus

    fun refresh(): CapabilityStatus {
        val cover = displayProvider.presentationDisplays().firstOrNull()
        currentStatus = if (postureProvider.current == FlipPosture.CLOSED && cover != null) {
            CapabilityStatus(
                available = true,
                details = mapOf(
                    "displayId" to cover.id.toString(),
                    "coverDisplaySize" to "${cover.width}x${cover.height}",
                ),
            )
        } else {
            CapabilityStatus(available = false)
        }
        return currentStatus
    }
}
