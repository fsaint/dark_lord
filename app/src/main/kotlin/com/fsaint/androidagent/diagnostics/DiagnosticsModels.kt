package com.fsaint.androidagent.diagnostics

data class DiagnosticEvent(val id: String, val type: String, val source: String, val payload: Map<String, String> = emptyMap())
data class DiagnosticCapability(val id: String, val available: Boolean, val details: Map<String, String> = emptyMap())
data class DiagnosticSnapshot(
    val events: List<DiagnosticEvent>,
    val capabilities: List<DiagnosticCapability>,
    val scopes: List<String>,
    val connections: List<String>,
    val skills: List<String>,
    val memoryNamespaces: List<String>,
    val trace: List<String>,
    val permissions: Map<String, Boolean>,
    val audits: List<String>,
)

data class DebugEventFixture(val type: String, val payload: Map<String, String> = emptyMap())
