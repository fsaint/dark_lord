package com.fsaint.androidagent.oem.samsungflip3

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Flip3CoverDisplayTest {
    @Test
    fun physicalFlip3ExposesIts512x260PresentationDisplay() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val cover = AndroidDisplayProvider(context).presentationDisplays().firstOrNull {
            it.width == 512 && it.height == 260
        }

        assumeTrue("Close the Flip3 to make the cover display available to apps", cover != null)
    }

    @Test
    fun closedFlip3PublishesCoverUiCapabilityWhenPresentationDisplayExists() {
        val capability = Flip3FormFactorCapability(
            displayProvider = FakeDisplayProvider(
                listOf(DisplayDescriptor(id = 7, width = 512, height = 260, isPresentation = true)),
            ),
            postureProvider = FakePostureProvider(FlipPosture.CLOSED),
        )

        val status = capability.refresh()

        assertTrue(status.available)
        assertEquals("512x260", status.details.getValue("coverDisplaySize"))
    }

    @Test
    fun postureChangeKeepsAssistantTaskIdAndSwitchesRendererOpen() {
        val posture = FakePostureProvider(FlipPosture.CLOSED)
        val controller = CoverDisplayController(
            displayProvider = FakeDisplayProvider(
                listOf(DisplayDescriptor(id = 7, width = 512, height = 260, isPresentation = true)),
            ),
            postureProvider = posture,
            taskId = "assistant-task",
        )

        val cover = controller.presentation()
        posture.current = FlipPosture.OPEN
        val open = controller.presentation()

        assertEquals("assistant-task", cover.taskId)
        assertEquals(cover.taskId, open.taskId)
        assertEquals(AssistantRenderer.OPEN, open.renderer)
    }

    private class FakeDisplayProvider(private val displays: List<DisplayDescriptor>) : DisplayProvider {
        override fun presentationDisplays(): List<DisplayDescriptor> = displays
    }

    private class FakePostureProvider(override var current: FlipPosture) : PostureProvider
}
