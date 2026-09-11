# Live MCP Agent Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. The transport, catalog and harness interfaces are tightly coupled; execute inline with independent final review.

**Goal:** Saved no-auth HTTPS MCP servers expose real, scoped tools to every conversational entry point.

**Architecture:** A bounded Streamable HTTP client performs initialization, discovery and calls. An application-owned manager produces immutable authorized tool snapshots; a shared harness extension supplies these to the model and executes selected remote calls through existing effect tracking.

**Tech Stack:** Kotlin, coroutines, kotlinx JSON, HttpURLConnection, Compose Material 3, existing Room configuration storage.

**Spec:** `docs/superpowers/specs/2026-09-09-live-mcp-agent-integration-design.md`

## Global Constraints

- Work directly on master as the user requested. Preserve the existing dirty chat/photo changes; no push, physical install, reset, or live external tool invocation.
- No OAuth, arbitrary authentication headers, stdio, legacy HTTP+SSE, inbound MCP, remote resources/prompts, sampling, elicitation or tasks in this increment.
- HTTPS only, normal TLS validation, no automatic redirects; private/Tailscale endpoints allowed when explicitly configured by the owner.
- Per discovery operation 10 seconds; aggregate discovery 20 seconds; call 60 seconds; 10 pages; 64 tools/server; 128 remote tools/request; 32 KiB/schema; 1 MiB/protocol response; five-minute successful catalog cache.
- Accept tested negotiated versions 2025-11-25, 2025-06-18, 2025-03-26. Preserve session/version headers. Never automatically replay a dispatched tool call.
- Owner access and existing MCP grants enforced at discovery and execution. Arguments remain typed JSON. Server descriptions/results are untrusted data. No base64, credentials or session IDs in diagnostics.

## Task 1: Bounded protocol client

**Files:** new `core/mcp/.../LiveMcpClient.kt`, `McpStreamTransport.kt`, `UrlConnectionMcpTransport.kt` and matching tests; add JSON dependency in `core/mcp/build.gradle.kts`. Existing legacy MCP foundations retain compatibility.

**Interfaces:** `McpStreamTransport.exchange(McpExchangeRequest, suspend (JsonObject)->Boolean): McpStreamResponse`; `LiveMcpClient.discover(endpoint): McpDiscovery`; `LiveMcpClient.call(session, name, arguments:JsonObject): JsonObject`. Message callback returns true when the matching response has arrived. Discovery owns a negotiated `McpSession` and typed tool schemas.

- [x] Write failing protocol tests using a fake wire transport, exercising real client JSON: initialize, initialized, pagination, typed calls, matching IDs, version/session propagation, auth and malformed responses.
  ```kotlin
  assertEquals("tools/list", requests[2]["method"]!!.jsonPrimitive.content)
  assertEquals(3, capturedArguments["count"]!!.jsonPrimitive.int)
  ```
- [x] Run `:core:mcp:test` red, then implement protocol lifecycle and structured JSON parsing. Reject repeated cursors, unsupported schema forms, invalid endpoints/headers and unknown protocol versions; retain safe notices for omitted tools.
- [x] Add streaming boundary tests for JSON, multiline SSE, unrelated notifications, size/depth limits, malformed/truncated streams and transport cancellation. Implement byte-bounded reads that stop at the matching result; unsupported server requests get method-not-found responses. Do not reconnect/replay calls automatically.
- [x] Run `:core:mcp:test` green.

## Task 2: Authorized connection manager

**Files:** new `core/mcp/.../McpConnectionManager.kt`, matching test, new model-facing descriptor in `core/policy/.../AuthorizationPolicy.kt`.

**Interfaces:** `McpConnectionManager(configurations:suspend()->List<McpConnection>, scopes, client)` exposes `snapshot(session): McpSnapshot`, `refresh(id)`, `invalidate(id)`, `states:StateFlow<Map<String,McpConnectionState>>`, and `execute(session, snapshot, call):ToolResult<Any>`. Snapshot maps stable aliases to server ID/original name/schema and keeps safe inventory/status text.

- [x] Write failure tests for no network on denied discovery/calls, collisions, stale removal, one failing server, typed argument preservation, caching/refresh, unknown effect outcome and result sanitization.
  ```kotlin
  assertTrue(manager.snapshot(guest).tools.isEmpty())
  assertEquals(ToolError.SCOPE_DENIED, manager.execute(guest, ownerSnapshot, call).error)
  ```
- [x] Run focused tests red. Implement per-server locks, config revalidation, stable SHA-256 aliases, cache expiry and bounded aggregate discovery. Failed servers do not affect phone tools. Session expiry invalidates future work without repeating the failed call.
- [x] Bound text/structured results; report unsupported binary blocks without forwarding base64. Surface accurate statuses, last-check timestamps, counts and notices.
- [x] Run focused tests green.

## Task 3: Shared harness, provider and durable argument integration

**Files:** runtime `ConversationHarness.kt`, `OpenAiResponsesProvider.kt`; new runtime `McpConversationExtension.kt`; runtime MCP dependency; data `RoomConversationCheckpointStore.kt`; matching runtime/data tests.

**Interfaces:** `ConversationToolExtension.prepare(session): ConversationToolSnapshot`; snapshot owns descriptors/inventory and a session-bound execute callback. Harness constructor gets an optional extension; request gets default-empty remote descriptors/status. Enrich once at the shared execute boundary, never per model loop.

- [x] Write an end-to-end failing test from saved config through actual manager, fake wire server, real provider JSON and harness execution. Also test unauthorized aliases and unchanged phone-only calls.
  ```kotlin
  assertEquals("object", modelTools.single()["parameters"]!!.jsonObject["type"]!!.jsonPrimitive.content)
  assertTrue(result.response!!.contains("remote result"))
  ```
- [x] Run red. Add remote descriptors to provider schemas with explicit safe aliases. Parse remote arguments into an internal canonical JSON envelope, leaving phone arguments unchanged. Pass remote calls through harness effect reservation/cancellation with snapshot and fresh authorization checks.
- [x] Write checkpoint round-trip tests for nested JSON, arrays, booleans, null, ampersands and legacy rows. Implement versioned structured JSON encoding with backward-compatible decoding; preserve tool result success/error and call arguments.
- [x] Run runtime/data tests green. Audit that SMS, Telegram, local API, typed, photo and voice share the modified harness instance.

## Task 4: App wiring, settings and verification

**Files:** app `DarkLordApplication.kt`, `MainActivity.kt`, `ui/McpSettingsScreen.kt`; README/getting-started; new verification report.

- [x] Test decoding existing saved configurations and endpoint validation before persistence. Instantiate the manager using existing Room configurations and connect the shared harness extension. Invalidate immediately on remove; no configuration rewrite or database-version change required.
- [x] Add observable status, tool list/count, last-check time, Refresh and clear no-auth scope messaging to the existing Material 3 settings. Keep networking off main and fail independently per server.
- [x] Run `./gradlew test :app:lintDebug :app:assembleDebug --no-daemon --max-workers=2`; inspect outputs and `git diff --check`.
- [x] Obtain independent spec/code review, address substantive findings and rerun affected tests. Update docs and report actual automated coverage; mark live phone/server acceptance pending. Leave changes uncommitted so unrelated chat edits are not accidentally included.

## Execution record

Plan reviewed against the approved spec. Tasks 1→2→3 share explicit protocol/catalog interfaces; task 4 consumes them without moving authorization into UI. Checkpoint changes in task 3 preserve existing rows. All tasks preserve no-auth scope and physical-device safety. Inline execution selected because the interfaces and shared app wiring are tightly coupled.

### Completed verification

- Implemented all four tasks inline and obtained independent read-only code review. Addressed timeout isolation, removal/publication races, cancellation cleanup, schema validation, explicit result truncation, and malformed tool-result handling. Final review has no remaining substantive findings.
- Observed red tests before the corresponding fixes; final `JAVA_HOME=/opt/homebrew/opt/openjdk ./gradlew test :app:lintDebug :app:assembleDebug --no-daemon --max-workers=2` completed successfully. Collected JVM/Robolectric XML reports contain 525 test-case executions, zero failures/errors/skips. MCP module: 41 tests. Lint: zero errors, 63 warnings across the existing app tree.
- New deterministic coverage includes protocol versions, paginated discovery, session headers, typed calls, missing capability, unsupported server requests, JSON/SSE bounds, cancellation of a blocked connection, authorized aliases, removal races, deadline isolation, unknown outcomes, result validation/truncation/binary omission, saved configuration decoding, real provider/harness integration and legacy/versioned checkpoint decoding. Existing phone-only tests remain green. Shared app wiring was inspected for all conversational entry points; those channels were not individually exercised on a device.
- Scope clarification: schemas use a conservative validated subset. References, regex constraints and unknown schema keywords are omitted with an explicit notice, never silently rewritten. Model definitions explicitly use non-strict schema mode for accepted remote schemas.
- README/getting-started now describe live no-auth integration and explicitly distinguish pending OAuth/inbound hosting. Detailed sanitized evidence: `docs/device-test/2026-09-09-live-mcp-integration.json`.
- No physical-device interaction, install, live external MCP/model call, configuration deletion, commit or push occurred during implementation. Existing dirty chat/photo changes were preserved. Phone/server acceptance remains pending.
