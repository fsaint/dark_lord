package com.fsaint.androidagent

import com.fsaint.androidagent.model.ToolCall
import com.fsaint.androidagent.model.ToolError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BrowserToolsTest {
    @Test
    fun rejectsNonHttpsAndCredentialBearingUrls() {
        val browser = BrowserTools()

        assertEquals(ToolError.SCOPE_DENIED, browser.open(ToolCall("browser.open", mapOf("url" to "http://example.com"))).error)
        assertEquals(ToolError.SCOPE_DENIED, browser.open(ToolCall("browser.open", mapOf("url" to "https://user:pass@example.com"))).error)
    }

    @Test
    fun readAndBackRequireAnOpenedPage() {
        val browser = BrowserTools()

        assertFalse(browser.read().success)
        assertEquals(ToolError.NOT_FOUND, browser.back().error)
    }
}
