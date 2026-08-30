package com.fsaint.androidagent.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DiagnosticsRepositoryTest {
    @Test fun `snapshot redacts sensitive values`() {
        val repo = DiagnosticsRepository(
            events = listOf(DiagnosticEvent("e1", "sms.received", "+15551212", mapOf("body" to "secret", "status" to "ok"))),
            permissions = mapOf("notifications" to true),
        )
        val snapshot = repo.snapshot()
        assertEquals("[REDACTED]", snapshot.events.single().payload["body"])
        assertEquals("ok", snapshot.events.single().payload["status"])
        assertTrue(repo.export().contains("[REDACTED]"))
        assertTrue(!repo.export().contains("secret"))
    }

    @Test fun `snapshot and export are bounded`() {
        val repo = DiagnosticsRepository(
            events = (1..20).map { DiagnosticEvent("e$it", "test", "local", mapOf("x" to "y")) },
            maxItems = 3,
            maxExportChars = 180,
        )
        assertEquals(3, repo.snapshot().events.size)
        assertTrue(repo.export().length <= 180)
    }

    @Test fun `injection accepts only typed local fixtures`() {
        val repo = DiagnosticsRepository()
        repo.inject(DebugEventFixture("device.health", mapOf("status" to "ok")))
        assertEquals("device.health", repo.snapshot().events.single().type)
        assertFailsWith<IllegalArgumentException> { repo.inject(DebugEventFixture("sms.received", mapOf("body" to "x"))) }
        assertFailsWith<IllegalArgumentException> { repo.inject(DebugEventFixture("unknown", emptyMap())) }
    }
}
