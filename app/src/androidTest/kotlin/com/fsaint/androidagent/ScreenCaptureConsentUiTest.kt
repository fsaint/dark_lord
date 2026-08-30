package com.fsaint.androidagent

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.fsaint.androidagent.ui.OpenAssistantScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ScreenCaptureConsentUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun screenCaptureConsentIsStartedOnlyAfterExplicitButtonTap() {
        var requests = 0
        compose.setContent {
            OpenAssistantScreen(
                onRequestAssistantRole = {},
                onRequestCapabilityPermissions = {},
                onRequestScreenCapture = { requests += 1 },
            )
        }

        assertEquals(0, requests)

        compose.onNodeWithText("Allow one screen capture").performClick()

        assertEquals(1, requests)
    }
}
