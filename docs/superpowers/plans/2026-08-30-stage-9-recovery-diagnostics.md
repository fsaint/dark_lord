# Stage 9 Recovery and Diagnostics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Android agent recover pending work after reboot, schedule durable tasks, expose explicit Device Owner setup, and provide a bounded diagnostics/debug surface.

**Architecture:** Keep scheduling and boot restoration orchestration in Android-free `:core:runtime` ports with deterministic tests. Android adapters use WorkManager, BroadcastReceiver, and DeviceAdminReceiver; the app UI reads redacted snapshots through injected diagnostics ports and never exposes secrets. OEM diagnostics report capability posture without assuming Samsung-only APIs in core.

**Tech Stack:** Kotlin/JVM, coroutines, AndroidX WorkManager, Android BroadcastReceiver/DeviceAdminReceiver, Jetpack Compose, JUnit 5, Android instrumentation.

**Spec:** `docs/superpowers/specs/2026-08-29-android-agent-design.md` (Operations, policy, and failure handling; Samsung Flip3 assistant experience)

## Global Constraints

- Events and schedules are durable; boot restoration is idempotent and preserves pending work.
- Device Owner provisioning is explicit and never silently claimed by the app.
- Recovery order is scopes, skills, capabilities, schedules, MCP, runtime.
- Diagnostics redact credentials, refresh tokens, message bodies, and private file contents; exports contain identifiers and dispositions only.
- Debug actions are owner/local-only and bounded; event injection must use typed fixtures and must not bypass authorization.

### Task 1: Define scheduler and boot-recovery ports

**Files:**
- Modify: `core/runtime/build.gradle.kts`
- Create: `core/runtime/src/main/kotlin/com/fsaint/androidagent/runtime/Scheduler.kt`
- Create: `core/runtime/src/main/kotlin/com/fsaint/androidagent/runtime/BootCoordinator.kt`
- Test: `core/runtime/src/test/kotlin/com/fsaint/androidagent/runtime/SchedulerTest.kt`
- Test: `core/runtime/src/test/kotlin/com/fsaint/androidagent/runtime/BootCoordinatorTest.kt`

- [ ] Write tests for deterministic schedule validation, idempotent replacement, cancellation, and exact boot restoration order.
- [ ] Implement typed scheduler/restore ports with bounded retry metadata and no Android dependencies.
- [ ] Return structured `NOT_FOUND`, `TIMEOUT`, and `OS_RESTRICTED` outcomes without dropping pending work.
- [ ] Run `./gradlew :core:runtime:test` and commit `feat: add durable scheduler and boot coordinator`.

### Task 2: Add Android WorkManager and boot/device-owner wiring

**Files:**
- Create: `app/src/main/kotlin/com/fsaint/androidagent/BootReceiver.kt`
- Create: `app/src/main/kotlin/com/fsaint/androidagent/AgentDeviceAdminReceiver.kt`
- Create: `app/src/main/kotlin/com/fsaint/androidagent/RuntimeRestoreWorker.kt`
- Modify: `app/src/main/AndroidManifest.xml`, `app/build.gradle.kts`, application wiring
- Test: `app/src/androidTest/kotlin/com/fsaint/androidagent/BootRecoveryTest.kt`

- [ ] Test receiver/worker intent handling and that restoration is enqueued once after `BOOT_COMPLETED`.
- [ ] Register the boot receiver, device-admin metadata, and WorkManager worker with explicit exported/permission declarations.
- [ ] Implement idempotent unique work, constraints, backoff, and restoration delegation through `BootCoordinator`.
- [ ] Update the Device Owner guide with the exact package/component and reboot verification commands.
- [ ] Run focused app unit/lint/connected checks and commit `feat: add boot recovery and device owner wiring`.

### Task 3: Build bounded diagnostics ports and debug UI

**Files:**
- Create: `app/src/main/kotlin/com/fsaint/androidagent/diagnostics/DiagnosticsModels.kt`
- Create: `app/src/main/kotlin/com/fsaint/androidagent/diagnostics/DiagnosticsRepository.kt`
- Create: `app/src/main/kotlin/com/fsaint/androidagent/ui/DebugScreen.kt`
- Modify: `app/src/main/kotlin/com/fsaint/androidagent/MainActivity.kt`
- Test: `app/src/test/kotlin/com/fsaint/androidagent/diagnostics/DiagnosticsRepositoryTest.kt`
- Test: `app/src/androidTest/kotlin/com/fsaint/androidagent/DebugScreenTest.kt`

- [ ] Test redaction and bounded export for events, capabilities, scopes/principals, MCP/skills, memory, agent trace, permissions, and audit search.
- [ ] Implement read-only inspector snapshots plus owner/local event injection through typed fixture validation.
- [ ] Add Compose navigation from the existing setup screen to diagnostics and expose export as a redacted text/JSON artifact.
- [ ] Keep debug UI unavailable to remote unknown principals and reject malformed injection requests.
- [ ] Run app tests/lint and commit `feat: add bounded diagnostics interface`.

### Task 4: Integrate OEM diagnostics and verify recovery

- [ ] Add an OEM posture/diagnostic snapshot for Flip3 display capability and assistant role state without moving Samsung APIs into core.
- [ ] Run `./gradlew :core:runtime:test :app:testDebugUnitTest :app:lintDebug`.
- [ ] Install/debug-test on SM-F711U1, verify boot receiver registration and diagnostics navigation; record Device Owner provisioning as a manual reset-sensitive step.
- [ ] Run `git diff --check`, update README and Stage 9 device checklist, commit documentation, and push `master`.

