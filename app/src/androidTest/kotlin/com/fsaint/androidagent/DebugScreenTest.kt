package com.fsaint.androidagent

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import com.fsaint.androidagent.diagnostics.DiagnosticsRepository
import com.fsaint.androidagent.ui.DebugScreen
import org.junit.Rule
import org.junit.Test

class DebugScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun diagnosticsShowsBoundedHealthSections() {
        compose.setContent { DebugScreen(DiagnosticsRepository(), onBack = {}) }
        compose.onNodeWithText("Diagnostics").assertIsDisplayed()
        compose.onNodeWithText("Export redacted diagnostics").assertIsDisplayed()
        compose.onNodeWithText("Read-only local health snapshot. Sensitive values are redacted.").assertIsDisplayed()
    }
}
