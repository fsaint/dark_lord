# Task 3 Report: Boot, Process-Death, and Battery Guidance Recovery

## Summary

Implemented Task 3 recovery work across boot restore, process-death restart, and user-facing battery guidance.

- `RuntimeRestoreWorker` now restores boot dependencies first and then starts the Task 2 foreground service API instead of bypassing it.
- `BootRecoveryDependencies` now separates runtime lifecycle control from boot dependency restoration, so the worker can restore state without directly starting the polling loop.
- `DarkLordApplication.startBackgroundRuntime()` now short-circuits when the runtime is already active, preventing redundant service starts on restore paths.
- Added `BackgroundRuntimeSettings` and a Compose-visible `BackgroundRuntimeSettingsCard` with the exact guidance text requested and an intent helper that prefers `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and falls back to `ACTION_APPLICATION_DETAILS_SETTINGS`.
- `MainActivity` now exposes the battery settings entry point from the assistant screen.

## Files Changed

- Created `app/src/main/kotlin/com/fsaint/androidagent/BackgroundRuntimeSettings.kt`
- Created `app/src/androidTest/kotlin/com/fsaint/androidagent/BackgroundRuntimeRecoveryTest.kt`
- Modified `app/src/main/kotlin/com/fsaint/androidagent/BootRecoveryDependencies.kt`
- Modified `app/src/main/kotlin/com/fsaint/androidagent/RuntimeRestoreWorker.kt`
- Modified `app/src/main/kotlin/com/fsaint/androidagent/DarkLordApplication.kt`
- Modified `app/src/main/kotlin/com/fsaint/androidagent/MainActivity.kt`
- Modified `app/src/main/kotlin/com/fsaint/androidagent/ui/OpenAssistantScreen.kt`
- Modified `app/src/main/AndroidManifest.xml`
- Modified `app/src/androidTest/kotlin/com/fsaint/androidagent/BootRecoveryTest.kt`
- Modified `app/build.gradle.kts`
- Modified `gradle/libs.versions.toml`

## TDD / Red Phase

The task brief’s exact command is not supported by this Gradle task:

```bash
./gradlew :app:connectedDebugAndroidTest --tests '*BackgroundRuntimeRecoveryTest' --no-daemon
```

Output:

```text
Problem configuring task :app:connectedDebugAndroidTest from command line.
> Unknown command-line option '--tests'.
BUILD FAILED
```

I used the Android instrumentation-runner class filter for the red/green cycle instead.

Red command:

```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.fsaint.androidagent.BackgroundRuntimeRecoveryTest --no-daemon
```

Relevant red output:

```text
com.fsaint.androidagent.BackgroundRuntimeRecoveryTest > restoreWorkerRetriesBeforeReturningFailure FAILED
expected:<class androidx.work.ListenableWorker$Result$Retry> but was:<class androidx.work.ListenableWorker$Result$Success>

com.fsaint.androidagent.BackgroundRuntimeRecoveryTest > restoreWorkerRestoresDependenciesBeforeStartingForegroundServiceOnce FAILED
expected:<[restore, start]> but was:<[start]>

com.fsaint.androidagent.BackgroundRuntimeRecoveryTest > assistantScreenShowsBatteryGuidanceForReliableTelegramPolling FAILED
The component is not displayed
```

This established the missing behavior before implementation: the worker was not restoring dependencies before starting the service path, restore failures were not reaching retry logic through the tested path, and the battery guidance UI did not exist.

## Implementation Notes

- Added `BackgroundRuntimeRestorer` so boot restore work is no longer overloaded onto the service lifecycle coordinator.
- Kept `ExistingWorkPolicy.KEEP` in `BootReceiver`.
- Kept the worker retry/failure boundary in `doWork()` unchanged; only the restore implementation behind it changed.
- Added `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` so the request intent is valid when the platform exposes it.
- The new UI guidance is intentionally text-only plus one button so it remains safe with no assumptions about an unlocked UI during boot restore itself.

## Final Verification

Fresh verification command:

```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.fsaint.androidagent.BackgroundRuntimeRecoveryTest,com.fsaint.androidagent.BootRecoveryTest --no-daemon
```

Output:

```text
> Task :app:connectedDebugAndroidTest
Starting 9 tests on SM-F711U1 - 15

Finished 9 tests on SM-F711U1 - 15

BUILD SUCCESSFUL in 25s
```

Hygiene check:

```bash
git diff --check
```

Output: no whitespace errors.

## Concerns / Follow-Up

- The exact `--tests` form in the brief is not accepted by `connectedDebugAndroidTest` in this project. The equivalent runner filter used here is `-Pandroid.testInstrumentationRunnerArguments.class=...`.
- The duplicate-start guard is based on `BootRecoveryDependencies.coordinator.isRunning`. That covers the intended restore paths, but if future work introduces additional asynchronous startup states before `isRunning` flips true, that path may need a stronger in-flight gate.

## Review Fix: Direct-Boot WorkManager Safety

Review identified a high-severity issue in the original Task 3 boot receiver behavior: `BootReceiver` was still `directBootAware` and listened for `LOCKED_BOOT_COMPLETED`, but it immediately initialized `WorkManager`. That is not safe before first unlock and can fail on direct-boot storage boundaries.

### Fix

- Kept `BootReceiver` `directBootAware=true` so the app still safely receives locked-boot broadcasts.
- Added a small `BackgroundRuntimeRestoreScheduler` boundary in `BootReceiver`.
- Changed `LOCKED_BOOT_COMPLETED` handling to defer restore work entirely and avoid touching `WorkManager`.
- Added `USER_UNLOCKED` to the manifest receiver filter and enqueue path.
- Kept normal `BOOT_COMPLETED` scheduling intact.
- Kept `ExistingWorkPolicy.KEEP` and the existing `RuntimeRestoreWorker` retry/failure semantics unchanged.

### Red Verification

Command:

```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.fsaint.androidagent.BackgroundRuntimeRecoveryTest,com.fsaint.androidagent.BootRecoveryTest --no-daemon
```

Initial red failure after writing the new tests:

```text
e: ...BootRecoveryTest.kt: Unresolved reference 'scheduler'
e: ...BootRecoveryTest.kt: Unresolved reference 'BackgroundRuntimeRestoreScheduler'
BUILD FAILED
```

That confirmed the tests were targeting a missing production boundary before implementation.

### Final Verification

Fresh verification command:

```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.fsaint.androidagent.BackgroundRuntimeRecoveryTest,com.fsaint.androidagent.BootRecoveryTest --no-daemon
```

Output:

```text
> Task :app:connectedDebugAndroidTest
Starting 14 tests on SM-F711U1 - 15

Finished 14 tests on SM-F711U1 - 15

BUILD SUCCESSFUL in 26s
```

Covered behaviors:

- `LOCKED_BOOT_COMPLETED` does not call the scheduler and does not create restore work.
- `USER_UNLOCKED` enqueues exactly one unique restore request.
- `BOOT_COMPLETED` enqueues exactly one unique restore request.
- Worker restore still preserves dependency-restore ordering, foreground-service start, and retry/failure behavior.
