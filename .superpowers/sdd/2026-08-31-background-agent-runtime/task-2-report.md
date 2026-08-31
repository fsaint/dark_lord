# Task 2 Report: Visible Foreground Service

## Summary

Implemented `AgentRuntimeService` as a visible foreground service for the long-lived agent runtime. The service exposes `ACTION_START`, `ACTION_STOP`, and `ACTION_RESTART`, creates the `agent_runtime` notification channel, posts an ongoing notification with Stop and Restart actions, returns `START_STICKY`, and delegates runtime lifecycle work to the Task 1 `AgentRuntimeRecovery`/`AgentRuntimeCoordinator` boundary through `BootRecoveryDependencies.coordinator`.

`DarkLordApplication` now exposes `startBackgroundRuntime()` and `stopBackgroundRuntime()`. `MainActivity.onStart()` starts the foreground service when the app is actually foregrounded, avoiding the Android background-start crash observed when service start was attempted unconditionally from `Application.onCreate()`.

## Files Changed

- Created `app/src/main/kotlin/com/fsaint/androidagent/AgentRuntimeService.kt`
- Modified `app/src/main/AndroidManifest.xml`
- Modified `app/src/main/kotlin/com/fsaint/androidagent/DarkLordApplication.kt`
- Modified `app/src/main/kotlin/com/fsaint/androidagent/MainActivity.kt`
- Created `app/src/androidTest/kotlin/com/fsaint/androidagent/AgentRuntimeServiceTest.kt`

`AgentRuntimeCoordinator.kt` did not need a source change because Task 1 already exposed the required `start()`, `stop()`, and `isRunning` contract through `AgentRuntimeRecovery`.

## TDD / Red Phase

Command:

```bash
./gradlew :app:connectedDebugAndroidTest --tests '*AgentRuntimeServiceTest' --no-daemon
```

Output:

```text
Problem configuring task :app:connectedDebugAndroidTest from command line.
> Unknown command-line option '--tests'.
BUILD FAILED
```

The exact command from the task brief is not supported by this Android Gradle task. I used compile and instrumentation-runner filtering for the red/green cycle.

Command:

```bash
./gradlew :app:compileDebugAndroidTestKotlin --no-daemon
```

Relevant red output after aligning the test assertions with existing JUnit androidTest style:

```text
e: ...AgentRuntimeServiceTest.kt: Unresolved reference 'AgentRuntimeServiceCommandHandler'
e: ...AgentRuntimeServiceTest.kt: Unresolved reference 'AgentRuntimeService'
e: ...AgentRuntimeServiceTest.kt: Unresolved reference 'AgentRuntimeNotificationFactory'
e: ...AgentRuntimeServiceTest.kt: Unresolved reference 'AgentRuntimeForegroundController'
BUILD FAILED
```

This verified the test failed for the missing foreground service contract before implementation.

## Android 14+ Runtime Finding

An initial implementation followed the task brief literally with only the base `FOREGROUND_SERVICE` permission and no service type. Automated service contract tests passed, but a real device smoke launch on the connected Flip crashed the service:

```text
MissingForegroundServiceTypeException: Starting FGS without a type
callerApp=ProcessRecord{... com.fsaint.androidagent} targetSDK=35
```

Android’s official foreground-service type documentation says apps targeting Android 14/API 34+ must declare an appropriate service type and matching foreground-service type permission; otherwise `startForeground()` raises `MissingForegroundServiceTypeException` or `SecurityException`.

Source: https://developer.android.com/develop/background-work/services/fgs/service-types

I changed `AgentRuntimeService` to `android:foregroundServiceType="remoteMessaging"` and declared `android.permission.FOREGROUND_SERVICE_REMOTE_MESSAGING`. The service still declares no microphone, camera, screen, location, health, or other sensor foreground type.

## Final Automated Verification

Command:

```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.fsaint.androidagent.AgentRuntimeServiceTest --no-daemon
```

Output:

```text
> Task :app:connectedDebugAndroidTest
Starting 6 tests on SM-F711U1 - 15

Finished 6 tests on SM-F711U1 - 15

BUILD SUCCESSFUL in 19s
```

Command:

```bash
./gradlew :app:testDebugUnitTest --tests '*AgentRuntimeCoordinatorTest' --no-daemon
```

Output:

```text
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 7s
```

## Device Smoke Verification

Commands:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -W -n com.fsaint.androidagent/.MainActivity
adb shell dumpsys activity services com.fsaint.androidagent/.AgentRuntimeService
adb shell am force-stop com.fsaint.androidagent
```

Relevant output:

```text
Performing Streamed Install
Success

Status: ok
LaunchState: COLD
Activity: com.fsaint.androidagent/.MainActivity

isForeground=true foregroundId=7101 types=0x00000200
foregroundNoti=Notification(channel=agent_runtime ... flags=ONGOING_EVENT|FOREGROUND_SERVICE ... category=service actions=2 ...)
startCommandResult=1
```

The smoke check verified the actual `ContextCompat.startForegroundService()` path, real `startForeground()` call, notification channel, foreground state, and `START_STICKY` result on the connected Flip.

## Concerns / Follow-Up

- The task brief said to add only the base `FOREGROUND_SERVICE` permission, but the connected API 35 device enforces Android 14+ foreground-service type requirements. I used `remoteMessaging` plus `FOREGROUND_SERVICE_REMOTE_MESSAGING` to make the service runnable while preserving the no microphone/camera/sensor constraint.
- The exact requested Gradle command with `--tests` is not valid for `connectedDebugAndroidTest` in this project. The equivalent class filter used here is `-Pandroid.testInstrumentationRunnerArguments.class=com.fsaint.androidagent.AgentRuntimeServiceTest`.
- `MainActivity` was modified even though it was not listed in the Task 2 file list, because unconditional foreground-service start from `DarkLordApplication.onCreate()` crashed under a background/test process state. Starting from `MainActivity.onStart()` keeps the owner API on the application while using an allowed foreground app state.
- Task 3 should continue boot/process recovery work carefully: API 35 foreground-service background-start restrictions still apply, and recovery should not assume a foreground activity is available.

## Review Fixes

Implemented the required follow-up fixes from Task 2 review:

- Changed boot/device-locked restore so `RuntimeRestoreWorker` calls `BootRecoveryDependencies.foregroundStarter.start(context)` instead of `BootRecoveryDependencies.coordinator.restore()`. The default starter uses `ContextCompat.startForegroundService(context, AgentRuntimeService.startIntent(context))`; the foreground service then invokes `AgentRuntimeCoordinator.start()`. This preserves WorkManager retry/failure behavior around the service-start attempt and keeps runtime restoration behind the visible foreground service notification.
- Added a locked-boot regression test proving restore starts the foreground service path and does not call coordinator `start()`/`restore()` directly.
- Gated `MainActivity.onStart()` runtime startup behind `POST_NOTIFICATIONS` on Android 13+ and request that permission before starting the service when it has not been granted. Pre-Android-13 behavior remains immediate startup.
- Added permission-gate tests and a real `MainActivity` launch regression that grants notifications, launches the activity, and verifies via `dumpsys activity services` that the actual `AgentRuntimeService` reached `isForeground=true` with channel `agent_runtime`.
- Changed `ACTION_STOP` handling to return `Service.START_NOT_STICKY` while it stops the coordinator/service asynchronously, avoiding a null-intent sticky restart after an explicit stop.

An intermediate review-fix test run failed before the real-launch test was corrected:

```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.fsaint.androidagent.AgentRuntimeServiceTest,com.fsaint.androidagent.BootRecoveryTest --no-daemon
```

Relevant output:

```text
Starting 12 tests on SM-F711U1 - 15

com.fsaint.androidagent.AgentRuntimeServiceTest > foregroundActivityLaunchStartsRealRuntimeServiceWhenNotificationsAllowed[SM-F711U1 - 15] FAILED
    java.lang.AssertionError: Condition was not met before timeout

Finished 12 tests on SM-F711U1 - 15
BUILD FAILED
```

Root cause: the test asserted against an injected fake coordinator, but real `DarkLordApplication.onCreate()` overwrites `BootRecoveryDependencies.coordinator` with the production coordinator before the activity/service path runs. I changed the regression to assert the actual service foreground state instead of an overwritten fake.

Final review-fix verification:

```bash
./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin --no-daemon
```

Output:

```text
BUILD SUCCESSFUL in 11s
191 actionable tasks: 3 executed, 188 up-to-date
```

```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.fsaint.androidagent.AgentRuntimeServiceTest,com.fsaint.androidagent.BootRecoveryTest --no-daemon
```

Output:

```text
> Task :app:connectedDebugAndroidTest
Starting 12 tests on SM-F711U1 - 15

Finished 12 tests on SM-F711U1 - 15

BUILD SUCCESSFUL in 19s
321 actionable tasks: 7 executed, 314 up-to-date
```

```bash
./gradlew :app:testDebugUnitTest --tests '*AgentRuntimeCoordinatorTest' --no-daemon
```

Output:

```text
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 8s
215 actionable tasks: 3 executed, 212 up-to-date
```

```bash
git diff --check
```

Output: no whitespace errors.

Review-fix concerns:

- The real service launch regression uses `dumpsys activity services` because it needs to verify the actual Android service/notification path rather than only an injected fake. This is a connected-device regression and may need adjustment if future Android versions change `dumpsys` formatting.
- The `remoteMessaging` foreground-service type/permission remains intentionally present per review ruling and target-35 enforcement; no microphone, camera, location, health, or other sensor foreground-service types were added.
