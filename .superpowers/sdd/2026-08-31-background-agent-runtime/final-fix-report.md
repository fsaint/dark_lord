# Background agent runtime final fix report

Date: 2026-08-31

Baseline reviewed: `9971fd1..ca2a651`

Fix commit: this report is committed with the consolidated final-review fix wave.

## Outcome

All six Important findings from the final review are addressed. The service now owns and joins background agent work, command transitions are serialized, the Android 15 foreground-service declaration uses `specialUse`, sensor tools are denied to background sessions at both context and execution boundaries, all restore/start paths require a visible runtime notification, and the locked acceptance class no longer strands a secure device behind keyguard.

## Findings addressed

### 1. Foreground-service ownership and destruction order

- Added a restartable `ServiceOwnedRuntimeWorkScope` below the application supervisor. SMS, notification, and Telegram agent work is accepted only while the foreground runtime owns this scope.
- Telegram dispatch runs inside the managed polling job so its offset remains unacknowledged if cancellation interrupts the durable acceptance boundary.
- `AgentRuntimeCoordinator.stop()` stops and joins Telegram polling, then cancels and joins all service-owned queued work under a non-cancellable cleanup boundary.
- `AgentRuntimeService.onDestroy()` synchronously shuts down the command actor and coordinator before removing the foreground notification and cancelling the service scope. Repeated or overlapping shutdown callers share the coordinator stop barrier.
- Tests prove queued work is cancelled and joined, work is rejected after stop and accepted after restart, a completed poller does not escape the ownership cleanup, and the runtime stop precedes notification removal.

### 2. Serialized START/STOP/RESTART commands

- Replaced independent service-scope command launches with one FIFO `Channel` actor.
- START, STOP, and RESTART runtime transitions now execute through the same serialized consumer. STOP calls `stopSelfResult(startId)` only after runtime stop; an older STOP therefore cannot terminate a service that has already received a newer RESTART.
- Actor shutdown closes new admission, cancels/joins the consumer, and performs one final coordinator stop.
- A deterministic coroutine test blocks the first STOP, queues RESTART, releases STOP, and proves the final transition is running rather than losing the restart.

### 3. Android 15 foreground-service type

- Replaced the inapplicable `remoteMessaging` declaration and permission with:
  - `android:foregroundServiceType="specialUse"`
  - `android.permission.FOREGROUND_SERVICE_SPECIAL_USE`
  - `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE=user_authorized_persistent_agent_runtime`
- API 34+ promotion now supplies `ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE`.
- Instrumentation asserts the manifest type, permission, subtype, non-exported status, and absence of camera/microphone/remote-messaging types.
- A real launch on the target API 35 SM-F711U1 reached foreground state on channel `agent_runtime`. Cleared logcat contained no missing/invalid type, start-not-allowed, foreground timeout, security, or fatal exception.

Android references used for the ruling:

- <https://developer.android.com/about/versions/14/changes/fgs-types-required>
- <https://developer.android.com/develop/background-work/services/fgs/service-types>
- <https://developer.android.com/reference/android/content/pm/ServiceInfo>

### 4. No sensor tools in background sessions

- Added a channel/surface policy that permits `camera.*`, `microphone.*`, and `screen.*` only for explicit `FOREGROUND`, `LOCAL`, `VOICE`, or `CAPTURE` sessions.
- `ScopedContextBuilder` removes these tools before model context is built for Telegram, SMS, and notification sessions.
- `ScopedToolRouter` rechecks the same policy before handler lookup or execution, preventing a model-generated or injected call from bypassing context filtering.
- Router parameter tests cover all three sensor families across Telegram, SMS, and notification sessions and prove the handler never runs. Explicit foreground/voice/capture/local cases remain allowed.
- A conversation-harness test drives an SMS model response that requests a camera tool and proves the tool is denied without execution.

### 5. Notification permission and channel gating

- Added `BackgroundRuntimeNotificationGate`, which requires `POST_NOTIFICATIONS` on Android 13+, app notifications enabled, and a non-blocked runtime channel. A not-yet-created channel is allowed so the first visible launch can create it.
- Applied the gate to explicit application start, boot restore before dependency restoration, service START/RESTART, and sticky null-intent restoration.
- Rejected starts return `START_NOT_STICKY`, do not call `startForeground`, stop any existing runtime work, and request service shutdown.
- Main activity creates the channel, requests notification permission when needed, and exposes a direct Agent runtime notification-settings action alongside the existing battery settings guidance.
- Tests cover OS-version permission behavior, disabled app/channel behavior, sticky rejection, boot restore suppression before dependency work, and the settings intent/UI action.

Android notification behavior references:

- <https://developer.android.com/develop/ui/views/notifications/notification-permission>
- <https://developer.android.com/reference/android/app/NotificationChannel>

### 6. Locked acceptance isolation and status

- Renamed the misleading locked test to `nonSecureKeyguardAcceptsSmsBroadcastAndHandlerLevelNotificationTranslation`.
- The test now uses an assumption to skip on secure-keyguard devices before sleeping the display. On non-secure devices cleanup wakes and dismisses keyguard and verifies the device is no longer locked.
- Renamed the boot worker test so it no longer claims to exercise locked boot.
- Updated README, getting-started guidance, specification/plan, and the Stage 12 checklist with the current connected status and remaining manual secure-lock/fold evidence.
- Current secure API 35 result for `LockedFoldedRuntimeAcceptanceTest`: four passed, zero failed, one intentional skip. Post-run `dumpsys window policy` reported `showing=false`.

The added notification-settings action made two older Compose targets fall below the scroll viewport. The full-suite red run identified this deterministically; both tests now scroll to the target before clicking, and the rerun is green.

## Test evidence

TDD red evidence captured before implementation:

- `:core:policy:test` failed the new background sensor context/router cases.
- `:core:runtime:test` failed the malicious background camera harness case.
- App coordinator/service/recovery Android-test compilation failed until the work lifecycle, command shutdown, notification gate, and special-use declarations existed.
- The first final connected sweep failed two off-screen Compose interactions after the settings card grew; the focused five-test rerun reproduced both failures before their minimal scroll fix.

Green verification:

- `./gradlew testDebugUnitTest :core:model:test :core:policy:test :core:runtime:test :core:mcp:test :core:skills:test :test-support:test :app:compileDebugAndroidTestKotlin --no-daemon --max-workers=2` — `BUILD SUCCESSFUL`.
- `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.fsaint.androidagent.AgentRuntimeServiceTest,com.fsaint.androidagent.BackgroundRuntimeRecoveryTest,com.fsaint.androidagent.BootRecoveryTest --no-daemon` — 29 passed, 0 failed.
- `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.fsaint.androidagent.LockedFoldedRuntimeAcceptanceTest --no-daemon` — 4 passed, 0 failed, 1 intentional secure-keyguard skip.
- `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.fsaint.androidagent.PrototypeAcceptanceTest,com.fsaint.androidagent.ScreenCaptureConsentUiTest --no-daemon --max-workers=2` — 5 passed, 0 failed after the scroll fix.
- `./gradlew :app:connectedDebugAndroidTest --no-daemon --max-workers=2` — `BUILD SUCCESSFUL`; result XML: 47 tests, 46 passed, 0 failed, 1 intentional secure-keyguard skip.
- `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.fsaint.androidagent.AgentRuntimeServiceTest#foregroundActivityLaunchStartsRealForegroundServiceWhenNotificationsAllowed --no-daemon --max-workers=2` after `adb logcat -c` — 1 passed; no foreground-service or fatal errors in logcat.
- `git diff --check` — clean before final staging; repeated after the report and before commit.

One unbounded all-variant Gradle attempt (`test testDebugUnitTest`) exhausted the Kotlin compiler heap while compiling unrelated release variants. The bounded debug/unit gate above was rerun successfully and is the applicable verification for this debug/API 35 fix wave.

## Remaining operator evidence and limits

- Secure-PIN locked and physically folded live Telegram, carrier SMS, and real Notification Access delivery remain manual because instrumentation must not attempt to unlock or leave the shared device secured.
- Force-stop/relaunch, Doze/OEM throttling, network loss, and live duplicate-delivery behavior still require the Stage 12 operator sequence.
- A foreground service improves reachability but does not override Android/Samsung background restrictions or explicit force-stop.
