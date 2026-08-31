# Background Agent Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep Dark Lord’s event-driven agent available while the phone is folded and locked by hosting its long-lived runtime in a visible Android foreground service.

**Architecture:** Add a narrowly scoped `AgentRuntimeService` that owns Telegram polling and runtime lifecycle, with idempotent start/stop commands. Keep SMS, notification-listener, and in-call behavior in their existing Android-managed components. Add boot recovery, notification actions, and a settings deep link for battery/background guidance; do not put microphone, camera, screen capture, or browser work into the persistent service.

**Tech Stack:** Kotlin, Android `Service`/foreground service, coroutines, WorkManager, Jetpack Compose, JUnit/Android instrumentation tests.

**Spec:** `docs/superpowers/specs/2026-08-31-background-agent-runtime.md`

## Global Constraints

- Minimum SDK remains 31 and target SDK remains 35.
- Background operation is user-visible through an ongoing notification.
- No microphone, camera, screen capture, or hidden UI automation starts from the persistent service.
- Telegram polling must have one active job at a time and preserve its existing durable offset/idempotency boundary.
- Folded and locked behavior must never assume an activity or display is available.

---

### Task 1: Extract a testable runtime lifecycle coordinator

**Files:**
- Create: `app/src/main/kotlin/com/fsaint/androidagent/AgentRuntimeCoordinator.kt`
- Modify: `app/src/main/kotlin/com/fsaint/androidagent/DarkLordApplication.kt`
- Test: `app/src/test/kotlin/com/fsaint/androidagent/AgentRuntimeCoordinatorTest.kt`

**Interfaces:**
- Consumes: `TelegramUpdateService.start(): Job`, `TelegramUpdateService.stop()`, and an application-owned `CoroutineScope`.
- Produces: `AgentRuntimeCoordinator.start()`, `suspend stop()`, `isRunning`, and an idempotent `restore()` used by boot recovery and the service.

- [ ] **Step 1: Write failing lifecycle tests**

```kotlin
@Test fun startIsIdempotent() = runTest {
    val updates = FakeUpdates()
    val coordinator = AgentRuntimeCoordinator(updates)
    coordinator.start(); coordinator.start()
    assertEquals(1, updates.starts)
}

@Test fun stopWaitsForPollingToFinish() = runTest {
    val updates = FakeUpdates()
    val coordinator = AgentRuntimeCoordinator(updates)
    coordinator.start(); coordinator.stop()
    assertEquals(1, updates.stops)
    assertFalse(coordinator.isRunning)
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*AgentRuntimeCoordinatorTest' --no-daemon`

Expected: FAIL because the coordinator and fake lifecycle port do not exist.

- [ ] **Step 3: Implement the coordinator**

Use a mutex or synchronized lifecycle boundary. `start()` may launch only one polling job; `stop()` must call the existing Telegram stop method and clear the active job. `restore()` must call `start()` and be safe after process recreation.

- [ ] **Step 4: Wire application teardown and boot recovery through the coordinator**

Replace direct `telegramUpdates.start()` with the coordinator, and configure `BootRecoveryDependencies.coordinator` with the same application-owned coordinator. Preserve the existing durable checkpoint store.

- [ ] **Step 5: Run the focused test and commit**

Run: `./gradlew :app:testDebugUnitTest --tests '*AgentRuntimeCoordinatorTest' --no-daemon`

Expected: PASS.

Commit: `git add app/src/main app/src/test && git commit -m "refactor: centralize background runtime lifecycle"`

### Task 2: Add the visible foreground service

**Files:**
- Create: `app/src/main/kotlin/com/fsaint/androidagent/AgentRuntimeService.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/kotlin/com/fsaint/androidagent/AgentRuntimeCoordinator.kt`
- Test: `app/src/androidTest/kotlin/com/fsaint/androidagent/AgentRuntimeServiceTest.kt`

**Interfaces:**
- Consumes: `AgentRuntimeCoordinator.start()` and `stop()`.
- Produces: `AgentRuntimeService.ACTION_START`, `ACTION_STOP`, `ACTION_RESTART`, and an ongoing notification channel named `agent_runtime`.

- [ ] **Step 1: Add service contract tests**

Assert that start/restart commands call `startForeground()` and coordinator start exactly once, stop calls coordinator stop, and the notification is ongoing with a stop action.

- [ ] **Step 2: Add Android service declaration and permissions**

Declare the service as non-exported with `android:stopWithTask="false"`. Add only the base `FOREGROUND_SERVICE` permission; do not declare microphone/camera types for this service.

- [ ] **Step 3: Implement notification and command handling**

Create the notification channel on Android O+, build an ongoing low-importance notification, call `startForeground()` before starting runtime work, and use `START_STICKY`. Handle `ACTION_STOP` by stopping the coordinator and service; handle `ACTION_RESTART` by stopping then starting once.

- [ ] **Step 4: Start the service from the coordinator owner**

Expose `startBackgroundRuntime()` and `stopBackgroundRuntime()` on `DarkLordApplication`; use `ContextCompat.startForegroundService()` for start and an explicit stop intent for stop. The service must not launch activities while locked.

- [ ] **Step 5: Run instrumentation tests and commit**

Run: `./gradlew :app:connectedDebugAndroidTest --tests '*AgentRuntimeServiceTest' --no-daemon`

Expected: PASS on the connected Flip; commit `feat: run agent runtime in foreground service`.

### Task 3: Boot, process-death, and battery-management recovery

**Files:**
- Modify: `app/src/main/kotlin/com/fsaint/androidagent/BootReceiver.kt`
- Modify: `app/src/main/kotlin/com/fsaint/androidagent/RuntimeRestoreWorker.kt`
- Create: `app/src/main/kotlin/com/fsaint/androidagent/BackgroundRuntimeSettings.kt`
- Modify: `app/src/main/kotlin/com/fsaint/androidagent/MainActivity.kt`
- Test: `app/src/androidTest/kotlin/com/fsaint/androidagent/BackgroundRuntimeRecoveryTest.kt`

**Interfaces:**
- Consumes: `AgentRuntimeService` intents and existing WorkManager boot restore.
- Produces: settings intents for `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` when available and `ACTION_APPLICATION_DETAILS_SETTINGS` fallback.

- [ ] **Step 1: Test boot recovery and duplicate prevention**

Verify `LOCKED_BOOT_COMPLETED` queues exactly one restore request and that the restore path starts the foreground service only once.

- [ ] **Step 2: Implement service-aware boot restore**

Have `RuntimeRestoreWorker` invoke the application’s `startBackgroundRuntime()` after restoring dependencies. Keep retry limits and `ExistingWorkPolicy.KEEP`.

- [ ] **Step 3: Add battery/background settings entry points**

Provide a Compose-visible action that opens the app battery settings. Show exact guidance: allow background activity, disable battery optimization for Dark Lord if the user wants reliable Telegram polling, and keep notifications enabled.

- [ ] **Step 4: Run recovery tests and commit**

Run: `./gradlew :app:connectedDebugAndroidTest --tests '*BackgroundRuntimeRecoveryTest' --no-daemon`

Expected: PASS; commit `feat: add background recovery guidance`.

### Task 4: Locked/folded acceptance coverage and documentation

**Files:**
- Create: `docs/device-test/stage-12-background-runtime.md`
- Modify: `README.md`
- Modify: `docs/getting-started.md`
- Test: `app/src/androidTest/kotlin/com/fsaint/androidagent/LockedFoldedRuntimeAcceptanceTest.kt`

**Interfaces:**
- Consumes: the foreground service, notification actions, and settings guidance from Tasks 1–3.
- Produces: a repeatable high-impact device test and user-facing limitations.

- [ ] **Step 1: Add instrumentation checks**

Verify the service notification exists, SMS/notification events are accepted while keyguard is locked, Telegram polling resumes after force-stop/relaunch, and a second start does not create duplicate replies.

- [ ] **Step 2: Run the device test sequence**

On the Flip: start Dark Lord, confirm the persistent notification, enable battery-unrestricted mode, fold and lock the phone, send one owner Telegram message and one SMS, confirm one reply for each, unlock and inspect diagnostics, then stop/restart from the notification.

- [ ] **Step 3: Document hard limits**

State that Android/OEM policy can still delay or stop work, UI actions may require unlock, and browser/microphone/camera/screen capture are on-demand rather than continuous background operations.

- [ ] **Step 4: Run the full verification suite and commit**

Run: `./gradlew testDebugUnitTest connectedDebugAndroidTest --no-daemon`

Expected: PASS on the connected device; commit `docs: document locked folded runtime testing`.
