package com.fsaint.androidagent.diagnostics

/** Read-only, bounded diagnostics projection. Providers must supply already-authorized data. */
class DiagnosticsRepository(
    events: List<DiagnosticEvent> = emptyList(),
    private val capabilities: List<DiagnosticCapability> = emptyList(),
    private val scopes: List<String> = emptyList(),
    private val connections: List<String> = emptyList(),
    private val skills: List<String> = emptyList(),
    private val memoryNamespaces: List<String> = emptyList(),
    private val trace: List<String> = emptyList(),
    private val permissions: Map<String, Boolean> = emptyMap(),
    private val audits: List<String> = emptyList(),
    private val maxItems: Int = 50,
    private val maxExportChars: Int = 32_000,
) {
    private val events = events.toMutableList()

    fun snapshot(): DiagnosticSnapshot = DiagnosticSnapshot(
        events = events.take(maxItems).map { it.redacted() },
        capabilities = capabilities.take(maxItems).map { it.copy(details = it.details.redact()) },
        scopes = scopes.take(maxItems), connections = connections.take(maxItems), skills = skills.take(maxItems),
        memoryNamespaces = memoryNamespaces.take(maxItems), trace = trace.take(maxItems),
        permissions = permissions.entries.take(maxItems).associate { it.toPair() }, audits = audits.take(maxItems),
    )

    fun inject(fixture: DebugEventFixture) {
        require(fixture.type in allowedFixtures) { "unsupported fixture" }
        require(fixture.payload.size <= 12 && fixture.payload.values.all { it.length <= 512 }) { "fixture is too large" }
        events += DiagnosticEvent("debug-${events.size + 1}", fixture.type, "local-debug", fixture.payload)
    }

    fun export(): String = buildString {
        append(snapshot().toString())
        if (length > maxExportChars) setLength(maxExportChars)
    }

    private fun DiagnosticEvent.redacted() = copy(payload = payload.redact())
    private fun Map<String, String>.redact() = mapValues { (key, value) -> if (key.lowercase() in sensitiveKeys) "[REDACTED]" else value.take(512) }
    private companion object {
        val sensitiveKeys = setOf("body", "message", "content", "token", "refresh_token", "secret", "password", "file_contents")
        val allowedFixtures = setOf("device.health", "capability.status", "runtime.ping")
    }
}
