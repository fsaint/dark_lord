# Task 4 report: locked/folded acceptance coverage and documentation

## Summary

Added Stage 12 background runtime acceptance coverage and documentation.

- Created `app/src/androidTest/kotlin/com/fsaint/androidagent/LockedFoldedRuntimeAcceptanceTest.kt`.
- Created `docs/device-test/stage-12-background-runtime.md`.
- Updated `README.md` to link Stage 12 and describe the foreground runtime service.
- Updated `docs/getting-started.md` with battery/background setup, Stage 12 validation, and hard-limit guidance.

No production runtime code was changed. The test consumes the Task 1 runtime coordinator, Task 2 foreground service/notification actions, Task 3 foreground startup and battery guidance assumptions, and the existing SMS/notification/Telegram acceptance seams.

## Acceptance coverage

`LockedFoldedRuntimeAcceptanceTest` covers:

- Real foreground-service launch from `MainActivity`, with `dumpsys activity services` proving `isForeground=true`, `channel=agent_runtime`, and notification id `7101`.
- The notification factory exposes an ongoing notification with **Stop** and **Restart** actions.
- SMS and notification-listener events are accepted through Android-managed entry points without depending on a foreground activity.
- `SharedPreferencesTelegramUpdateCheckpointStore` restores the first Telegram poll offset after a fresh `TelegramUpdateService` instance, matching the force-stop/relaunch recovery boundary.
- `AgentRuntimeCoordinator.start()` remains idempotent so a second start does not create duplicate reply work.

The initial version attempted to force the device into real sleep/keyguard state inside the automated suite. That focused class passed, but the subsequent full connected suite showed Compose tests cascading with `No compose hierarchies found in the app`; device inspection showed the Flip remained on a secure keyguard/bouncer. I removed the forced `KEYCODE_SLEEP` behavior from the automated test. The physical folded/locked validation is now explicitly documented as an operator-run Stage 12 device sequence.

## Documentation

`docs/device-test/stage-12-background-runtime.md` now documents:

- The focused connected instrumentation command.
- Expected automated assertions.
- A manual folded and locked sequence: launch Dark Lord, confirm persistent notification, enable unrestricted battery/background mode, fold and lock, send one owner Telegram message and one SMS, confirm one reply for each, inspect diagnostics, then stop/restart from the notification.
- Required evidence to record.
- Hard limits: Android/Samsung policy can delay or stop work; UI actions may require unlock; browser, microphone, camera, and screen capture are on-demand rather than continuous background operations.

`README.md` and `docs/getting-started.md` now point to Stage 12 and surface the same limits for operators.

## Commands and output

Focused connected acceptance, first version:

```sh
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.fsaint.androidagent.LockedFoldedRuntimeAcceptanceTest --no-daemon
```

Output:

```text
> Task :app:connectedDebugAndroidTest
Starting 4 tests on SM-F711U1 - 15

Finished 4 tests on SM-F711U1 - 15

BUILD SUCCESSFUL in 24s
```

Full requested suite attempt:

```sh
./gradlew testDebugUnitTest connectedDebugAndroidTest --no-daemon
```

Relevant output before interruption:

```text
> Task :app:connectedDebugAndroidTest
Starting 40 tests on SM-F711U1 - 15

com.fsaint.androidagent.BackgroundRuntimeRecoveryTest > assistantScreenShowsBatteryGuidanceForReliableTelegramPolling[SM-F711U1 - 15] FAILED
    java.lang.IllegalStateException: No compose hierarchies found in the app.

com.fsaint.androidagent.DebugScreenTest > diagnosticsShowsBoundedHealthSections[SM-F711U1 - 15] FAILED
    java.lang.IllegalStateException: No compose hierarchies found in the app.

com.fsaint.androidagent.OwnerProvisioningUiTest > successfulProvisioningReplacesSetupWithOwnerStatus[SM-F711U1 - 15] FAILED
    java.lang.IllegalStateException: No compose hierarchies found in the app.

com.fsaint.androidagent.OwnerProvisioningUiTest > setupControlsAreHiddenWhenOwnerExists[SM-F711U1 - 15] FAILED
    java.lang.IllegalStateException: No compose hierarchies found in the app.

com.fsaint.androidagent.OwnerProvisioningUiTest > submitRemainsDisabledUntilExplicitConfirmation[SM-F711U1 - 15] FAILED
    java.lang.IllegalStateException: No compose hierarchies found in the app.
```

I interrupted this run with Ctrl-C after repeated identical Compose hierarchy failures. Root-cause evidence from the connected Flip:

```sh
adb shell dumpsys window | rg -n "mCurrentFocus|mFocusedApp|mAwake|mScreenOn|mDreamingLockscreen|mKeyguard"
```

Output:

```text
mCurrentFocus=Window{874c8ba u0 Bouncer}
mFocusedApp=ActivityRecord{354e72c u0 com.sec.android.app.launcher/.activities.LauncherActivity t582}
mShowingDream=false mDreamingLockscreen=true
```

After removing the forced sleep/keyguard step, focused connected acceptance was rerun:

```sh
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.fsaint.androidagent.LockedFoldedRuntimeAcceptanceTest --no-daemon
```

Output:

```text
> Task :app:connectedDebugAndroidTest
Starting 4 tests on SM-F711U1 - 15

Finished 4 tests on SM-F711U1 - 15

BUILD SUCCESSFUL in 26s
```

Unit suite:

```sh
./gradlew testDebugUnitTest --no-daemon
```

Output:

```text
BUILD SUCCESSFUL in 19s
```

Whitespace check:

```sh
git diff --check
```

Output: no whitespace errors.

## Concerns

- The exact full command `./gradlew testDebugUnitTest connectedDebugAndroidTest --no-daemon` did not complete successfully in this session because the connected Flip remained on a secure keyguard/bouncer after the first locked-state attempt. A human operator needs to unlock the device before the full connected suite can be rerun.
- The automated acceptance test no longer forces the phone into a physical locked/folded state because that made the shared connected suite non-repeatable. The real folded/locked scenario is covered by the new manual Stage 12 checklist.
- The connected acceptance test still validates the app-owned event acceptance boundaries, foreground notification state, Telegram checkpoint recovery seam, and duplicate-start guard on `SM-F711U1 - 15`.
