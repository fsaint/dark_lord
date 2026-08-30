# Dark Lord Agent Harness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the prototype’s one-shot planner path with a durable, policy-enforced harness that can use Android capabilities, configured MCP servers, declarative skills, memory, and all supported response channels.

**Architecture:** Keep the harness Android-free in `:core:runtime`; expose typed tools through a merged catalog whose Android, MCP, and skill providers are independently scope-filtered. Persist run state and transcript boundaries in encrypted Room, use the OpenAI Responses API through a replaceable provider interface, and let Android adapters own transport, TTS/STT, roles, permissions, and Tailscale sockets.

**Tech Stack:** Kotlin 2.2, Coroutines/Flow, Jetpack Compose Material 3, Room/SQLCipher, Android Keystore, OpenAI Responses API, Streamable HTTP MCP, OAuth 2.0, Tailscale, WorkManager, JUnit 5, Robolectric, and connected Android tests.

**Spec:** `docs/superpowers/specs/2026-08-30-agent-harness-design.md`

## Global Constraints

- Android 12+ support; primary validation target is Samsung Galaxy Z Flip3 SM-F711U1 on Android 15/API 35.
- Authorization, secret access, confirmation, and verification remain below model control.
- Owner actions execute autonomously only inside existing grants and Android permissions.
- Unknown principals retain the minimal default scope; known-principal access requires explicit grants.
- External MCP uses HTTPS Streamable HTTP and OAuth; local stdio and legacy SSE are excluded.
- Downloaded skills are declarative only; downloaded executable code is prohibited.
- Credentials, refresh tokens, private file contents, prompts, and raw tool arguments never enter diagnostics or ordinary audit output.
- Every model run has an eight-turn maximum; at most four independent tool calls execute in parallel.
- Every tool call has a timeout, bounded payload, idempotency key, normalized error, and verification state.
- SMS sends one final response; progress belongs in trace/audit only.

## File and Module Map

- `core/runtime/.../AgentHarness.kt`: run lifecycle, model/tool turns, cancellation, resume, and terminal states.
- `core/runtime/.../AgentContracts.kt`: request, response, tool definition, run state, policy, and trace contracts.
- `core/runtime/.../ToolCatalog.kt`: merged Android/MCP/skill discovery and namespacing.
- `core/runtime/.../ExecutionSupervisor.kt`: bounded parallel execution, timeout, cancellation, and idempotency.
- `core/runtime/.../ContextAssembler.kt`: scoped transcript, memory, skills, and tool definitions.
- `core/runtime/.../AgentRuntime.kt`: event admission and channel response integration.
- `core/data/.../AgentDatabase.kt`: `AgentRun`, tool-call checkpoint, and transcript schema plus migration.
- `core/data/.../HarnessRepositories.kt`: Room implementations for run, transcript, memory, and trace stores.
- `core/mcp/...`: MCP discovery/call adapter, OAuth token lifecycle, and Tailscale listener.
- `core/skills/...`: skill activation and proposal boundaries consumed by the catalog.
- `app/.../DarkLordApplication.kt`: dependency graph, providers, catalogs, and recovery wiring.
- `app/.../ui/McpSettingsScreen.kt`: owner-only MCP configuration and connection state.
- `app/.../voice/...`: transcript input and TTS response adapters.
- `app/src/androidTest/...`: device acceptance for harness, MCP, voice, and recovery.
- `docs/getting-started.md`, `docs/device-test/stage-11-conversational-harness.md`: operator setup and evidence.

---

### Task 1: Lock the Android-free harness contracts

**Files:**
- Create: `core/runtime/src/main/kotlin/com/fsaint/androidagent/runtime/AgentContracts.kt`
- Create: `core/runtime/src/main/kotlin/com/fsaint/androidagent/runtime/ToolCatalog.kt`
- Modify: `core/runtime/src/main/kotlin/com/fsaint/androidagent/runtime/RuntimePorts.kt`
- Test: `core/runtime/src/test/kotlin/com/fsaint/androidagent/runtime/AgentContractsTest.kt`

**Interfaces:**
- `AgentRequest(runId: String, session: ScopedAgentSession, event: AgentEvent, userText: String)`
- `ModelRequest(request: AgentRequest, transcript: ConversationTranscript, memory: Map<String, List<String>>, skills: List<SkillDefinition>, tools: List<ToolDefinition>)`
- `ModelResponse = ToolCalls(calls: List<ToolCall>) | Final(text: String) | Escalate(question: String, reason: String)`
- `ToolDefinition(id, description, argumentSchema, source, requiredResource, confirmation, timeoutMillis, concurrencyKey)`
- `ToolProvider.discover(scope: ScopeSnapshot): List<ToolDefinition>` and `ToolProvider.execute(scope, call): ToolResult<Any>`
- `AgentHarness.run`, `resume`, and `cancel` methods with `AgentRunResult` and `AgentRunState` terminal values.

- [ ] **Step 1: Write failing contract tests** for a final response, a tool response, escalation, invalid tool IDs, and terminal-state serialization.
- [ ] **Step 2: Run** `./gradlew :core:runtime:test --tests '*AgentContractsTest'`; verify the new types are absent or assertions fail.
- [ ] **Step 3: Implement** the sealed contracts and validation rules, including non-empty IDs, positive timeouts, eight-turn and four-call constants, and safe error enums.
- [ ] **Step 4: Run** the focused test and `./gradlew :core:runtime:compileKotlin`; both must pass.
- [ ] **Step 5: Commit** `git commit -m "feat: define durable agent harness contracts"`.

### Task 2: Implement the bounded execution supervisor

**Files:**
- Create: `core/runtime/src/main/kotlin/com/fsaint/androidagent/runtime/ExecutionSupervisor.kt`
- Test: `core/runtime/src/test/kotlin/com/fsaint/androidagent/runtime/ExecutionSupervisorTest.kt`

**Interfaces:**
- Consumes `ToolProvider`, `ScopeSnapshot`, and validated `ToolCall` values from Task 1.
- Produces `ExecutionBatchResult(results: List<ToolResult<Any>>, cancelled: Boolean)` and idempotency keys.

- [ ] **Step 1: Write tests** proving four calls run, a fifth is rejected, same-concurrency-key calls serialize, timeouts become `TIMEOUT`, cancellation propagates, and duplicate idempotency keys do not execute twice.
- [ ] **Step 2: Run** `./gradlew :core:runtime:test --tests '*ExecutionSupervisorTest'`; verify failure.
- [ ] **Step 3: Implement** coroutine-based bounded execution with `Semaphore(4)`, keyed mutexes, `withTimeout`, cancellation propagation, and an injected execution ledger.
- [ ] **Step 4: Run** focused tests and confirm all race-sensitive assertions pass under `runTest`.
- [ ] **Step 5: Commit** `git commit -m "feat: bound tool execution and retries"`.

### Task 3: Build context assembly and merged tool catalog

**Files:**
- Create: `core/runtime/src/main/kotlin/com/fsaint/androidagent/runtime/ContextAssembler.kt`
- Modify: `core/runtime/src/main/kotlin/com/fsaint/androidagent/runtime/ToolCatalog.kt`
- Modify: `core/policy/src/main/kotlin/com/fsaint/androidagent/policy/AuthorizationPolicy.kt`
- Test: `core/runtime/src/test/kotlin/com/fsaint/androidagent/runtime/ToolCatalogTest.kt`

**Interfaces:**
- Consumes `ScopedContextBuilder`, `ToolProvider`, `SkillProvider`, and `ConversationStore`.
- Produces `AssembledContext(transcript, memory, skills, tools, scopeSnapshot)`.

- [ ] **Step 1: Test** that owner context includes permitted local/MCP/skill definitions, unknown context excludes them, MCP IDs are namespaced, and execution rechecks scope after discovery.
- [ ] **Step 2: Run** the focused test and verify failure.
- [ ] **Step 3: Implement** stable namespacing (`android.<id>`, `mcp.<connection>.<tool>`, `skill.<id>`), bounded transcript/memory selection, and immutable scope snapshots.
- [ ] **Step 4: Run** focused runtime and policy tests.
- [ ] **Step 5: Commit** `git commit -m "feat: assemble scoped agent context"`.

### Task 4: Replace the one-shot runtime with the harness loop

**Files:**
- Create: `core/runtime/src/main/kotlin/com/fsaint/androidagent/runtime/AgentHarness.kt`
- Modify: `core/runtime/src/main/kotlin/com/fsaint/androidagent/runtime/AgentRuntime.kt`
- Modify: `core/runtime/src/test/kotlin/com/fsaint/androidagent/runtime/AgentRuntimeTest.kt`
- Create: `core/runtime/src/test/kotlin/com/fsaint/androidagent/runtime/AgentHarnessTest.kt`

**Interfaces:**
- Consumes Tasks 1–3 and existing `EventStore`, `AuditStore`, `ReplySender`, and `EscalationService`.
- Produces durable turn transitions: `ACCEPTED`, `MODEL_WAITING`, `TOOL_RUNNING`, `FINAL`, `ESCALATED`, `CANCELLED`, `TURN_LIMIT`, `FAILED`.

- [ ] **Step 1: Add failing tests** for model → tool → model → final, multi-tool execution, escalation, turn limit, provider failure, one final reply, and cancellation without false completion.
- [ ] **Step 2: Run** `./gradlew :core:runtime:test --tests '*AgentHarnessTest'`; verify failure.
- [ ] **Step 3: Implement** the loop with checkpoint writes before/after every model/tool boundary and no rerun of completed idempotent calls.
- [ ] **Step 4: Update legacy runtime tests** to verify existing planner behavior remains available only through a compatibility adapter, then run all runtime tests.
- [ ] **Step 5: Commit** `git commit -m "feat: run events through durable agent harness"`.

### Task 5: Add Room run, transcript, memory, and trace persistence

**Files:**
- Modify: `core/data/src/main/kotlin/com/fsaint/androidagent/data/AgentDatabase.kt`
- Create: `core/data/src/main/kotlin/com/fsaint/androidagent/data/HarnessRepositories.kt`
- Modify: `core/data/src/main/kotlin/com/fsaint/androidagent/data/Repositories.kt`
- Create: `core/data/schemas/com.fsaint.androidagent.data.AgentDatabase/4.json`
- Test: `core/data/src/test/kotlin/com/fsaint/androidagent/data/HarnessRepositoryTest.kt`

**Interfaces:**
- Consumes runtime stores from Task 4.
- Produces `RoomAgentRunStore`, `RoomConversationStore`, `RoomMemoryStore`, and `RoomTraceStore`.

- [ ] **Step 1: Write migration/integration tests** for run state transitions, transcript ordering, tool-result durability, process recreation, and idempotency lookup.
- [ ] **Step 2: Run** `./gradlew :core:data:test`; verify migration tests fail.
- [ ] **Step 3: Add** `agent_runs`, `agent_turns`, and `agent_traces` entities/DAOs, migration `3→4`, bounded queries, and encrypted-content encoding without raw secrets.
- [ ] **Step 4: Run** Room schema generation and all data tests; verify schema JSON is checked in.
- [ ] **Step 5: Commit** `git commit -m "feat: persist agent runs and checkpoints"`.

### Task 6: Wire OpenAI Responses to the new model contract

**Files:**
- Modify: `core/runtime/src/main/kotlin/com/fsaint/androidagent/runtime/OpenAiResponsesProvider.kt`
- Create: `core/runtime/src/main/kotlin/com/fsaint/androidagent/runtime/OpenAiResponseCodec.kt`
- Modify: `core/runtime/src/test/kotlin/com/fsaint/androidagent/runtime/OpenAiResponsesProviderTest.kt`

**Interfaces:**
- Consumes `ModelRequest` and produces `ModelResponse`.
- Keeps `OpenAiHttpTransport`, `OpenAiApiKeyProvider`, and `OwnerOnlyOpenAiCredentialStore` replaceable.

- [ ] **Step 1: Add fixtures/tests** for Responses `output_text`, `function_call` name/arguments, multiple calls, malformed JSON, oversized responses, HTTP errors, and key redaction.
- [ ] **Step 2: Run** focused provider tests and verify new response cases fail.
- [ ] **Step 3: Implement** structured JSON encoding/decoding, bounded input/tool schemas, argument maps, and normalized provider errors.
- [ ] **Step 4: Run** all runtime tests and verify no API key appears in request errors or traces.
- [ ] **Step 5: Commit** `git commit -m "feat: connect responses provider to harness"`.

### Task 7: Integrate MCP discovery, OAuth, and execution

**Files:**
- Modify: `core/mcp/src/main/kotlin/com/fsaint/androidagent/mcp/StreamableHttpMcpClient.kt`
- Modify: `core/mcp/src/main/kotlin/com/fsaint/androidagent/mcp/ScopedMcpRouter.kt`
- Create: `core/mcp/src/main/kotlin/com/fsaint/androidagent/mcp/McpToolProvider.kt`
- Modify: `core/mcp/src/test/kotlin/com/fsaint/androidagent/mcp/ScopedMcpRouterTest.kt`
- Create: `core/mcp/src/test/kotlin/com/fsaint/androidagent/mcp/McpToolProviderTest.kt`

**Interfaces:**
- Consumes saved `McpConnection`, `OAuthManager`, and `ScopeRegistry`.
- Produces a runtime `ToolProvider` with namespaced discovery and structured calls.

- [ ] **Step 1: Test** `tools/list` discovery, namespacing, OAuth refresh, scope denial, protocol errors, payload limits, and HTTPS-only enforcement.
- [ ] **Step 2: Run** `./gradlew :core:mcp:test`; verify provider tests fail.
- [ ] **Step 3: Implement** discovery caching with expiry, OAuth token acquisition/refresh through the secret store, JSON-RPC call mapping, and safe result conversion.
- [ ] **Step 4: Add** official Kotlin MCP SDK compatibility behind an adapter seam; keep current test transport as a deterministic fake.
- [ ] **Step 5: Run** all MCP tests and commit `git commit -m "feat: expose scoped MCP tools to harness"`.

### Task 8: Integrate declarative skills and memory compaction

**Files:**
- Create: `core/runtime/src/main/kotlin/com/fsaint/androidagent/runtime/SkillToolProvider.kt`
- Create: `core/runtime/src/main/kotlin/com/fsaint/androidagent/runtime/MemoryCompactor.kt`
- Modify: `core/skills/src/main/kotlin/com/fsaint/androidagent/skills/SkillLifecycle.kt`
- Test: `core/runtime/src/test/kotlin/com/fsaint/androidagent/runtime/SkillToolProviderTest.kt`
- Test: `core/runtime/src/test/kotlin/com/fsaint/androidagent/runtime/MemoryCompactorTest.kt`

**Interfaces:**
- Consumes active validated skill versions and scoped memory stores.
- Produces bounded `SkillDefinition` context and proposed memory/skill updates requiring policy approval.

- [ ] **Step 1: Test** inactive/ungranted skill exclusion, instruction size limits, compaction preserving current task/tool results, and rejected executable payloads.
- [ ] **Step 2: Run** focused tests and verify failure.
- [ ] **Step 3: Implement** declarative skill projection, session-memory search, compaction summaries, and owner-approved activation proposals.
- [ ] **Step 4: Run** runtime and skills test suites.
- [ ] **Step 5: Commit** `git commit -m "feat: add scoped skills and memory context"`.

### Task 9: Wire Android capabilities and channels into the catalog

**Files:**
- Modify: `app/src/main/kotlin/com/fsaint/androidagent/DarkLordApplication.kt`
- Modify: `app/src/main/kotlin/com/fsaint/androidagent/communications/CommunicationsDispatcher.kt`
- Modify: `app/src/main/kotlin/com/fsaint/androidagent/voice/VoiceCaptureService.kt`
- Create: `app/src/main/kotlin/com/fsaint/androidagent/voice/VoiceResponseRouter.kt`
- Modify: `app/src/test/kotlin/com/fsaint/androidagent/communications/CommunicationsDispatcherTest.kt`
- Create: `app/src/test/kotlin/com/fsaint/androidagent/voice/VoiceResponseRouterTest.kt`

**Interfaces:**
- Consumes all existing capability tool handlers and Task 4 `AgentRuntime`.
- Produces one shared harness path for SMS, VOICE, ASSISTANT, NOTIFICATION, and scheduled events.

- [ ] **Step 1: Test** channel normalization, owner voice identity, one SMS final response, TTS final response, and missing-key/provider failure behavior.
- [ ] **Step 2: Run** app unit tests and verify new cases fail.
- [ ] **Step 3: Replace** duplicate/legacy tool wiring with one catalog, route voice transcripts to the owner session, and keep TTS/SMS response adapters separate.
- [ ] **Step 4: Run** `./gradlew :app:testDebugUnitTest :app:compileDebugKotlin`.
- [ ] **Step 5: Commit** `git commit -m "feat: route Android channels through harness"`.

### Task 10: Complete owner MCP setup and scope administration UI

**Files:**
- Modify: `app/src/main/kotlin/com/fsaint/androidagent/ui/McpSettingsScreen.kt`
- Modify: `app/src/main/kotlin/com/fsaint/androidagent/MainActivity.kt`
- Modify: `app/src/main/kotlin/com/fsaint/androidagent/DarkLordApplication.kt`
- Test: `app/src/androidTest/kotlin/com/fsaint/androidagent/McpSettingsUiTest.kt`

**Interfaces:**
- Consumes persisted MCP repository and principal/scope repositories.
- Produces owner-only add/remove/test/grant controls with endpoint and OAuth metadata validation.

- [ ] **Step 1: Test** owner visibility, HTTPS validation, save/remove, scope grant display, and redaction of OAuth fields.
- [ ] **Step 2: Run** `./gradlew :app:compileDebugAndroidTestKotlin`; verify new test fails before implementation.
- [ ] **Step 3: Implement** connection health/test action, per-principal grant controls, loading/error/success states, and accessible labels.
- [ ] **Step 4: Run** connected UI tests on SM-F711U1.
- [ ] **Step 5: Commit** `git commit -m "feat: administer MCP connections and scopes"`.

### Task 11: Expose the inbound Dark Lord MCP server over Tailscale

**Files:**
- Modify: `core/mcp/src/main/kotlin/com/fsaint/androidagent/mcp/TailscaleMcpServer.kt`
- Create: `app/src/main/kotlin/com/fsaint/androidagent/mcp/TailscaleMcpSocketService.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `core/mcp/src/test/kotlin/com/fsaint/androidagent/mcp/TailscaleMcpServerTest.kt`
- Test: `app/src/androidTest/kotlin/com/fsaint/androidagent/TailscaleMcpServiceTest.kt`

**Interfaces:**
- Consumes `ScopedMcpRouter`, enrolled client identities, and the Android network interface provider.
- Produces a foreground-bound listener that publishes only authorized `android.*` tools.

- [ ] **Step 1: Test** Tailscale address selection, client authentication, malformed requests, size limits, scope denial, and clean shutdown.
- [ ] **Step 2: Run** focused MCP tests and verify socket/service tests fail.
- [ ] **Step 3: Implement** interface discovery for `100.64.0.0/10`, `ServerSocket` lifecycle on an explicit configured port, newline-delimited/HTTP request framing, authenticated client enrollment, and safe endpoint status reporting.
- [ ] **Step 4: Add** foreground service declaration and an owner UI status card showing active address/port only when the listener is bound.
- [ ] **Step 5: Run** connected service tests and commit `git commit -m "feat: expose scoped MCP endpoint over Tailscale"`.

### Task 12: Add scheduling, interruption UX, and reboot recovery

**Files:**
- Modify: `app/src/main/kotlin/com/fsaint/androidagent/RuntimeRestoreWorker.kt`
- Modify: `app/src/main/kotlin/com/fsaint/androidagent/BootReceiver.kt`
- Create: `core/runtime/src/main/kotlin/com/fsaint/androidagent/runtime/SchedulePlanner.kt`
- Modify: `core/runtime/src/test/kotlin/com/fsaint/androidagent/runtime/SchedulerTest.kt`
- Create: `app/src/androidTest/kotlin/com/fsaint/androidagent/HarnessRecoveryTest.kt`

**Interfaces:**
- Consumes durable run store, WorkManager, boot recovery coordinator, and harness cancellation/resume.
- Produces scheduled `AgentRequest` creation, process-death resume, and explicit cancellation state.

- [ ] **Step 1: Test** schedule creation, missed-run recovery, cancellation, and no duplicate delivery after reboot.
- [ ] **Step 2: Run** scheduler/recovery tests and verify failure.
- [ ] **Step 3: Implement** idempotent WorkManager jobs, boot restoration order, and run-state reconciliation.
- [ ] **Step 4: Run** JVM and connected recovery tests.
- [ ] **Step 5: Commit** `git commit -m "feat: recover and schedule agent runs"`.

### Task 13: Documentation, acceptance matrix, and release gate

**Files:**
- Modify: `README.md`
- Modify: `docs/getting-started.md`
- Modify: `docs/device-test/stage-11-conversational-harness.md`
- Create: `docs/device-test/stage-12-agent-harness.md`
- Modify: `docs/acceptance/flip3-prototype-checklist.md`

**Interfaces:**
- Consumes the completed harness, MCP, skills, and channel behaviors from Tasks 1–12.
- Produces operator-readable setup, troubleshooting, security, and evidence procedures.

- [ ] **Step 1: Add** exact commands for adding an MCP server, granting scopes, testing connectivity, finding the Tailscale endpoint, configuring the model, and testing SMS/voice/Assistant.
- [ ] **Step 2: Add** acceptance rows for model→Android tool, model→MCP tool, skill procedure, cancellation/resume, reboot, denial, escalation, secret redaction, and inbound MCP authentication.
- [ ] **Step 3: Run** link/checklist review and `git diff --check`.
- [ ] **Step 4: Commit** `git commit -m "docs: document complete agent harness acceptance"`.

### Task 14: Full verification and device sign-off

**Files:**
- No source changes unless verification identifies a concrete defect; fixes must be committed as separate focused commits.

- [ ] **Step 1: Run** `./gradlew test lintDebug :app:assembleRelease`.
- [ ] **Step 2: Run** `./gradlew :app:releaseSha256` and record the APK checksum.
- [ ] **Step 3: Install** the release APK on SM-F711U1, verify launch, owner key setup, MCP settings, and no crash on missing key.
- [ ] **Step 4: Execute** device acceptance: SMS tool request, voice request/TTS, Side-button Assistant, local Android tool, configured MCP tool, declarative skill, scope denial, owner escalation, process death/resume, reboot recovery, and Tailscale client authentication.
- [ ] **Step 5: Review** diagnostics for redaction and audit completeness; attach evidence to the Stage 12 checklist.
- [ ] **Step 6: Commit** any evidence/documentation update and push `master` only after the release gate is green.

## Self-review coverage

- Harness lifecycle and bounded execution: Tasks 1–4.
- Room persistence, recovery, and idempotency: Tasks 5 and 12.
- OpenAI provider and secret boundary: Task 6.
- Android capabilities and all channels: Task 9.
- MCP discovery, OAuth, scope filtering, and inbound Tailscale: Tasks 7, 10, and 11.
- Declarative skills, memory, and learning boundary: Task 8.
- Observability, diagnostics, docs, and acceptance: Tasks 4, 5, 9, 13, and 14.
- No placeholders or unresolved implementation decisions are left in this plan; version and transport constraints are explicit above.
