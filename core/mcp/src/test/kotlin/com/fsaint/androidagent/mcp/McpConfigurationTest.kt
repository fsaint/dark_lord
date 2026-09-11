package com.fsaint.androidagent.mcp

import org.junit.jupiter.api.Test
import kotlin.test.*

class McpConfigurationTest {
    @Test fun existingSavedConfigurationRetainsEndpointWithoutTreatingOauthFieldsAsCredentials() {
        val config = decodeMcpConfiguration("saved", "Private server", "https://host.example/mcp\u0000https://auth.example/token\u0000client".toByteArray())
        assertEquals("https://host.example/mcp", config.endpoint)
        assertEquals("saved", config.id)
    }
}
