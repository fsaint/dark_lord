package com.fsaint.androidagent.oem.samsungflip3

import android.content.Context
import android.hardware.display.DisplayManager

class AndroidDisplayProvider(context: Context) : DisplayProvider {
    private val displayManager = context.getSystemService(DisplayManager::class.java)

    override fun presentationDisplays(): List<DisplayDescriptor> =
        displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).map { display ->
            DisplayDescriptor(
                id = display.displayId,
                width = display.mode.physicalWidth,
                height = display.mode.physicalHeight,
                isPresentation = true,
            )
        }
}

/**
 * Android does not publish a stable closed-posture ID for every vendor. The cover renderer is
 * therefore gated on the capability Android does expose: a presentation display. This avoids
 * guessing from screen dimensions or intercepting device keys.
 */
class DisplayBackedPostureProvider(private val displayProvider: DisplayProvider) : PostureProvider {
    override val current: FlipPosture
        get() = if (displayProvider.presentationDisplays().isEmpty()) FlipPosture.OPEN else FlipPosture.CLOSED
}
