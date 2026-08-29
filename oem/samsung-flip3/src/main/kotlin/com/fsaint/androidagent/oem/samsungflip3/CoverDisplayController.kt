package com.fsaint.androidagent.oem.samsungflip3

enum class AssistantRenderer { OPEN, COVER }

data class AssistantPresentation(
    val taskId: String,
    val renderer: AssistantRenderer,
    val display: DisplayDescriptor? = null,
)

/**
 * Chooses a renderer from runtime display and posture state. It deliberately does not
 * receive or handle hardware-key events; Assistant invocation remains OS-owned.
 */
class CoverDisplayController(
    private val displayProvider: DisplayProvider,
    private val postureProvider: PostureProvider,
    private val taskId: String,
) {
    fun presentation(): AssistantPresentation {
        val cover = displayProvider.presentationDisplays().firstOrNull()
        return if (postureProvider.current == FlipPosture.CLOSED && cover != null) {
            AssistantPresentation(taskId, AssistantRenderer.COVER, cover)
        } else {
            AssistantPresentation(taskId, AssistantRenderer.OPEN)
        }
    }
}
