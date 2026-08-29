# Android Agent Prototype Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the no-root Android agent platform specified in [the approved design](../specs/2026-08-29-android-agent-design.md), including the Flip3 Assistant experience and all MVP A–F capabilities.

**Architecture:** A Kotlin multi-module Android app hosts a persistent, Room-backed agent runtime. Android features are independently registered capabilities; all tool, MCP, memory, and skill access crosses a scope-enforcing router below the OpenAI Responses planner. A Samsung Flip3 module owns posture, cover-display, and Assistant-session behavior.

**Tech Stack:** Kotlin, Jetpack Compose, Room, Coroutines/Flow, WorkManager, Android Keystore, `VoiceInteractionService`, Telecom, AccessibilityService, MediaProjection, CameraX, Android role APIs, OpenAI Responses API, Streamable HTTP MCP, OAuth 2.0, Tailscale.

**Spec:** `docs/superpowers/specs/2026-08-29-android-agent-design.md`

## Global Constraints

- `minSdk = 31`, `targetSdk = 35`, `compileSdk = 35`; primary device: Samsung SM-F711U1 on Android 15/API 35.
- Kotlin/Compose/Room only; do not introduce React Native, Flutter, or a backend proxy.
- All external model traffic uses the OpenAI Responses API from the device; credentials and OAuth tokens are Keystore-protected.
- Every Android capability returns the defined structured tool errors and reports unsupported hardware truthfully.
- Scope filtering occurs before model context construction and again at tool/MCP/memory/skill execution.
- Public Git/HTTP skill packages are declarative only. No downloaded DEX, JAR, native library, JavaScript, or WASM executes in v0.1.
- The MCP server is private to Tailscale and authenticates enrolled clients.
- Owner actions need no per-action confirmation; Android role, permission, OAuth, accessibility, and MediaProjection grants remain user-mediated setup.

---

## File Structure

| Path | Responsibility |
| --- | --- |
| `app/` | Compose setup, settings, debug UI, manifest, service wiring |
| `core/model/` | Stable event, tool, capability, session, scope, and result types |
| `core/data/` | Room schema, repositories, encryption envelope, migrations |
| `core/runtime/` | Event processor, context builder, planner, verification, audit |
| `core/policy/` | Principal/scope resolution and hard routers |
| `core/mcp/` | Streamable HTTP MCP client/server and OAuth configuration |
| `core/skills/` | Declarative package validation, activation, updates, rollback |
| `capabilities/*/` | One Android API adapter per capability group |
| `oem/samsung-flip3/` | Fold state, external display, cover UI, Assistant integration |
| `test-support/` | Fakes, fixture builders, deterministic model/capability adapters |

### Task 1: Bootstrap the Android project and test harness

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `app/`, `core/model/`, `core/data/`, `core/runtime/`, `core/policy/`, `core/mcp/`, `core/skills/`, `test-support/`, `capabilities/device/`, `oem/samsung-flip3/`
- Test: each module’s `src/test` source set

**Produces:** a compileable Android app plus pure Kotlin core modules and `./gradlew test` / `./gradlew connectedCheck` entry points.

- [ ] **Step 1: Create a failing architecture test**

```kotlin
@Test fun coreModulesDoNotDependOnAndroidUi() {
    assertThat(runtimeDependencies).doesNotContain("androidx.compose")
}
```

- [ ] **Step 2: Run it to verify failure**

Run: `./gradlew :core:runtime:testDebugUnitTest`

Expected: FAIL because the module and test do not exist.

- [ ] **Step 3: Create the module graph and version catalog**

Use `minSdk = 31`, `targetSdk = 35`, and `compileSdk = 35`. Keep Android framework adapters out of `core:model`, `core:policy`, and `core:runtime`; add `test-support` fixtures as test-only dependencies.

- [ ] **Step 4: Verify the baseline**

Run: `./gradlew test lintDebug`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add .
git commit -m "build: bootstrap modular Android agent project"
```

### Task 2: Define contracts and persist durable state

**Files:**
- Create: `core/model/src/main/kotlin/.../AgentEvent.kt`, `ToolResult.kt`, `AgentCapability.kt`, `ScopedAgentSession.kt`, `Scope.kt`
- Create: `core/data/src/main/kotlin/.../AgentDatabase.kt`, `EventRepository.kt`, `AuditRepository.kt`
- Test: `core/data/src/test/kotlin/.../EventRepositoryTest.kt`

**Interfaces:**

```kotlin
interface AgentCapability {
    val id: String
    val version: String
    suspend fun initialize(): CapabilityStatus
    fun tools(): List<AgentTool>
    fun events(): Flow<AgentEvent>
    fun status(): CapabilityStatus
}
data class ToolResult<T>(val success: Boolean, val payload: T?, val error: ToolError?, val recoverable: Boolean, val verification: VerificationState)
```

- [ ] **Step 1: Write failing persistence tests**

```kotlin
@Test fun eventIsRedeliveredAfterProcessRestartUntilCompleted() = runTest {
    repository.enqueue(event)
    assertThat(reopenedRepository.nextPending()).isEqualTo(event)
}
@Test fun auditRecordContainsAuthorizationAndVerification() = runTest {
    auditRepository.append(AuditRecord(tool = "device.battery", authorization = ALLOW, verification = VERIFIED))
    assertThat(auditRepository.list().single()).isEqualTo(AuditRecord(tool = "device.battery", authorization = ALLOW, verification = VERIFIED))
}
```

- [ ] **Step 2: Implement Room entities, DAO transactions, and repositories**

Persist principals, scope grants, sessions, events, delivery state, schedules, skills, skills versions, MCP configuration, OAuth metadata, escalations, tool executions, verification, and audit records. Encrypt user content with a Keystore-wrapped database key.

- [ ] **Step 3: Verify**

Run: `./gradlew :core:data:testDebugUnitTest`

Expected: PASS, including restart and transaction tests.

- [ ] **Step 4: Commit**

```bash
git add core/model core/data
git commit -m "feat: add durable agent contracts and storage"
```

### Task 3: Implement scope resolution and hard authorization

**Files:**
- Create: `core/policy/src/main/kotlin/.../PrincipalRegistry.kt`, `ScopeRegistry.kt`, `ScopedContextBuilder.kt`, `ScopedToolRouter.kt`, `ScopedMcpRouter.kt`, `ScopedMemoryProvider.kt`, `ScopedSkillRegistry.kt`
- Test: `core/policy/src/test/kotlin/.../ScopedToolRouterTest.kt`

**Interfaces:**

```kotlin
suspend fun ScopedToolRouter.execute(session: ScopedAgentSession, call: ToolCall): ToolResult<Any>
fun ScopedContextBuilder.build(session: ScopedAgentSession): AgentContext
```

- [ ] **Step 1: Write failing OWNER/KNOWN/UNKNOWN tests**

```kotlin
@Test fun unknownCannotCallLocationEvenWhenPlannerRequestsIt() = runTest {
    assertThat(router.execute(unknown, ToolCall("location.current", emptyMap())).error)
        .isEqualTo(ToolError.SCOPE_DENIED)
}
@Test fun coworkerGetsAvailabilityButNotPrivateCalendarDetails() = runTest {
    assertThat(contextBuilder.build(coworker).resources).contains("calendar.availability")
    assertThat(contextBuilder.build(coworker).memory).doesNotContain("Private meeting")
}
```

- [ ] **Step 2: Implement E.164 lookup, default scopes, grants, and deny-by-default routers**

- [ ] **Step 3: Verify**

Run: `./gradlew :core:policy:testDebugUnitTest`

Expected: PASS with no router path able to bypass grants.

- [ ] **Step 4: Commit**

```bash
git add core/policy core/model
git commit -m "feat: enforce multi-principal scopes below the model"
```

### Task 4: Build the runtime, OpenAI planner, memory, verification, and escalation loop

**Files:**
- Create: `core/runtime/src/main/kotlin/.../AgentRuntime.kt`, `EventProcessor.kt`, `OpenAiResponsesProvider.kt`, `VerificationEngine.kt`, `EscalationService.kt`
- Create: `test-support/src/main/kotlin/.../FakeModelProvider.kt`
- Test: `core/runtime/src/test/kotlin/.../AgentRuntimeTest.kt`

- [ ] **Step 1: Write failing vertical-loop tests**

```kotlin
@Test fun ownerBatteryRequestCallsPermittedToolThenSendsReply() = runTest {
    runtime.process(ownerSms("What's the battery?"))
    assertThat(fakeReply.sent.single().text).contains("72%")
}
@Test fun ownerReplyResumesPersistedEscalationSession() = runTest {
    escalationService.resolve(escalationId, OwnerDecision.Approve)
    assertThat(fakeReply.sent.single().recipient).isEqualTo(alice.phone)
}
```

- [ ] **Step 2: Implement event-to-terminal-state processing**

The runtime must persist before dispatch, build filtered context, expose only authorized tool schemas, require `ToolResult.verification == VERIFIED` before success language, and route recoverable failures to retry, fallback, escalation, or transparent response.

- [ ] **Step 3: Verify and commit**

Run: `./gradlew :core:runtime:testDebugUnitTest`

```bash
git add core/runtime test-support
git commit -m "feat: add scoped agent runtime and escalation loop"
```

### Task 5: Deliver the first Flip3 Assistant vertical slice

**Files:**
- Create: `oem/samsung-flip3/src/main/kotlin/.../Flip3FormFactorCapability.kt`, `CoverDisplayController.kt`, `AgentVoiceInteractionService.kt`, `AgentVoiceInteractionSession.kt`
- Create: `app/src/main/kotlin/.../OpenAssistantScreen.kt`, `CoverAssistantScreen.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `oem/samsung-flip3/src/androidTest/.../Flip3CoverDisplayTest.kt`

- [ ] **Step 1: Write device and fake-display tests**

```kotlin
@Test fun closedFlip3PublishesCoverUiCapabilityWhenPresentationDisplayExists() {
    assertThat(capability.status().details["coverDisplaySize"]).isEqualTo("512x260")
}
@Test fun postureChangeKeepsAssistantTaskAndChangesRenderer() {
    assertThat(sessionAfterOpen.id).isEqualTo(sessionWhileClosed.id)
    assertThat(sessionAfterOpen.renderer).isEqualTo(OPEN)
}
```

- [ ] **Step 2: Implement the supported Assistant pathway**

Declare `VoiceInteractionService` and `VoiceInteractionSession`, request `ROLE_ASSISTANT`, query display/posture at runtime, and render a compact Compose surface on the cover display only when available. Render the full Compose session when open. Do not intercept hardware keys directly.

- [ ] **Step 3: Add microphone/STT, battery, camera, and owner reply tools**

Use a foreground microphone service, speech segmentation, and the configured OpenAI provider. Register `device.battery`, `camera.capture`, and `sms.reply` through capability contracts.

- [ ] **Step 4: Verify on the physical Flip3**

Run: `./gradlew connectedCheck`

Manual expected result: after granting Assistant, microphone, camera, and SMS roles, a Side-key long press invokes the agent in both postures; a closed-phone spoken request displays and speaks a response.

- [ ] **Step 5: Commit**

```bash
git add app oem/samsung-flip3 capabilities/device
git commit -m "feat: add Flip3 assistant interaction vertical slice"
```

### Task 6: Add SMS, dialer, notifications, and principal administration

**Files:**
- Create: `capabilities/sms/`, `capabilities/telephony/`, `capabilities/notifications/`, `app/.../PrincipalSettingsScreen.kt`, `app/.../OwnerSmsCommandHandler.kt`
- Test: `capabilities/sms/src/androidTest/.../SmsEventTest.kt`, `capabilities/telephony/src/androidTest/.../CallStateTest.kt`

- [ ] **Step 1: Write failing message/call routing tests**

```kotlin
@Test fun receivedSmsIsPersistedThenResolvedToOwnerScope() = runTest {
    runtime.process(receivedSms(owner.phone, "battery"))
    assertThat(sessionRepository.latest().scopeId).isEqualTo("owner")
}
@Test fun unknownLocationQuestionProducesScopeDeniedSafeReply() = runTest {
    runtime.process(receivedSms(unknown.phone, "Where is the boss?"))
    assertThat(fakeTools.calls).doesNotContain("location.current")
}
```

- [ ] **Step 2: Implement default-SMS and default-dialer role flows**

Implement SMS receiver/default app functions, Telecom/InCallService call state/control, NotificationListenerService, owner-number SMS verification, local scope administration, and owner-authenticated administration commands.

- [ ] **Step 3: Verify**

Run: `./gradlew connectedCheck`

Manual expected result: real SMS and call events create scoped sessions; known-person escalation persists, alerts owner, resumes, and replies after the owner response.

- [ ] **Step 4: Commit**

```bash
git add app capabilities/sms capabilities/telephony capabilities/notifications
git commit -m "feat: add scoped communications and owner administration"
```

### Task 7: Implement general Android and physical-environment capabilities

**Files:**
- Create: `capabilities/accessibility/`, `screen/`, `apps/`, `camera/`, `bluetooth/`, `wifi/`, `audio/`, `location/`, `sensors/`, `nfc/`, `usb/`, `contacts/`, `files/`
- Test: one fake-adapter unit test and one instrumentation test per module

- [ ] **Step 1: Write capability contract tests for each module**

```kotlin
@Test fun unavailableNfcReturnsUnsupportedRatherThanSuccess() = runTest {
    assertThat(nfcCapability.readTag().error).isEqualTo(ToolError.UNSUPPORTED)
}
@Test fun secureWindowCaptureReturnsSecureWindow() = runTest {
    assertThat(screenCapability.capture(secureWindow).error).isEqualTo(ToolError.SECURE_WINDOW)
}
```

- [ ] **Step 2: Implement capability groups in this order**

Accessibility/apps/screen; camera/microphone/audio; Bluetooth/BLE/Wi-Fi; location/sensors; NFC/USB; contacts/files. Register every action and event through `AgentCapability`; do not call Android APIs from the runtime.

- [ ] **Step 3: Verify each group**

Run: `./gradlew test connectedCheck`

Manual expected result: execute acceptance tests 02–11 and 18 against the Flip3, recording actual unsupported outcomes in `capabilities.inspect()`.

- [ ] **Step 4: Commit each group separately**

```bash
git add capabilities/accessibility capabilities/apps capabilities/screen
git commit -m "feat: add Android UI capabilities"
```

Repeat the same test-then-commit cycle for media, radios, sensors, and I/O groups.

### Task 8: Add MCP, OAuth, Tailscale server, and declarative skill lifecycle

**Files:**
- Create: `core/mcp/src/main/kotlin/.../StreamableHttpMcpClient.kt`, `OAuthManager.kt`, `TailscaleMcpServer.kt`
- Create: `core/skills/src/main/kotlin/.../SkillPackageValidator.kt`, `SkillInstaller.kt`, `SkillUpdateService.kt`
- Test: `core/mcp/src/test/.../ScopedMcpRouterTest.kt`, `core/skills/src/test/.../SkillRollbackTest.kt`

- [ ] **Step 1: Write failing isolation and rollback tests**

```kotlin
@Test fun coworkerCannotDiscoverPersonalMcpConnection() = runTest {
    assertThat(scopedMcpRouter.toolsFor(coworker)).doesNotContain("personal_email.search")
}
@Test fun failedSkillUpdateLeavesPriorVersionActive() = runTest {
    installer.install(invalidUpdate)
    assertThat(skillRepository.activeVersion("schedule-meeting")).isEqualTo("1.2.0")
}
```

- [ ] **Step 2: Implement Streamable HTTP/OAuth and private inbound transport**

Restrict the server listener to the Tailscale address, require enrolled client authentication, store OAuth refresh tokens through Keystore, and filter every MCP tool through scope routers.

- [ ] **Step 3: Implement declarative registry lifecycle**

Validate YAML manifest and archive hash, reject executable payloads, dry-run examples/tests with fake tools, atomically activate versions, and preserve rollback candidates.

- [ ] **Step 4: Verify and commit**

Run: `./gradlew :core:mcp:testDebugUnitTest :core:skills:testDebugUnitTest`

```bash
git add core/mcp core/skills
git commit -m "feat: add scoped MCP and declarative skill updates"
```

### Task 9: Add scheduler, boot recovery, Device Owner, debug interface, and OEM diagnostics

**Files:**
- Create: `core/runtime/.../Scheduler.kt`, `app/.../BootReceiver.kt`, `app/.../AgentDeviceAdminReceiver.kt`, `app/.../DebugScreen.kt`
- Create: `docs/device-provisioning/galaxy-z-flip3-reset-and-device-owner.md`
- Test: `app/src/androidTest/.../BootRecoveryTest.kt`, `core/runtime/src/test/.../SchedulerTest.kt`

- [ ] **Step 1: Write failing persistence/recovery tests**

```kotlin
@Test fun rebootRestoresPendingEventsSchedulesAndCapabilityInitialization() = runTest {
    bootCoordinator.restore()
    assertThat(bootCoordinator.actions).containsExactly("scopes", "skills", "capabilities", "schedules", "mcp", "runtime")
}
```

- [ ] **Step 2: Implement WorkManager scheduling, boot restoration, DeviceAdminReceiver, and debug screens**

The debug UI must expose event injection, event monitor, tool/capability/scope/principal/MCP/skill/memory inspectors, agent trace, permissions, and audit search/export.

- [ ] **Step 3: Verify after reset/provisioning**

Run: `./gradlew connectedCheck`

Manual expected result: follow the device guide, provision Device Owner, reboot, and verify the foreground runtime restores without losing pending work.

- [ ] **Step 4: Commit**

```bash
git add app core/runtime docs/device-provisioning
git commit -m "feat: add recovery scheduler device owner and debug tools"
```

### Task 10: Run the complete acceptance suite and release a sideloadable build

**Files:**
- Create: `docs/acceptance/flip3-prototype-checklist.md`, `docs/release/sideloading.md`
- Modify: `app/build.gradle.kts`, `README.md`
- Test: `app/src/androidTest/.../PrototypeAcceptanceTest.kt`

- [ ] **Step 1: Encode acceptance scenarios as traceable tests/checklist entries**

Cover source-spec tests 01–28 plus Assistant invocation when open and closed, cover touch, voice request/response, reboot recovery, permission refusal, `SCOPE_DENIED`, and MCP OAuth failure.

- [ ] **Step 2: Run the release gate**

Run: `./gradlew test lintDebug connectedCheck assembleRelease`

Expected: all unit/instrumentation tests pass and `app/build/outputs/apk/release/` contains the signed sideloadable artifact.

- [ ] **Step 3: Execute the real-device checklist and export audit evidence**

Expected: each scenario records a timestamped audit trail and unsupported device boundaries are marked honestly.

- [ ] **Step 4: Commit**

```bash
git add app docs README.md
git commit -m "docs: add validated Flip3 prototype release checklist"
```

## Plan self-review

- Spec coverage: Tasks 1–4 implement the substrate, memory, event bus, scopes, agent loop, audit, and escalation; Tasks 5–7 implement Flip3, communication, UI, and physical capabilities; Tasks 8–9 implement MCP, skills, scheduler, Device Owner, and diagnostics; Task 10 verifies all stated acceptance scenarios.
- No-placeholder scan: no deferred requirements are left without an explicit owning task.
- Type consistency: all capability integration uses `AgentCapability`, all tool execution uses `ToolResult<T>`, and all authorization crosses `Scoped*` routers defined in Task 3.
