# Stage 12 background runtime device checklist

Use this checklist on the Samsung Galaxy Z Flip3 running Android 15 after installing a current debug or release build of Dark Lord. The goal is to prove the visible foreground service keeps event-driven agent work reachable while the phone is folded and locked, within Android and OEM policy limits.

## Automated acceptance check

Run this connected instrumentation check from an unlocked device. The test starts the foreground runtime and verifies notification controls. The SMS broadcast and handler-level notification translation check enters keyguard only when the device uses a non-secure keyguard that instrumentation can dismiss. It is intentionally skipped when a PIN, pattern, or password is configured, so the class never leaves the device locked for later tests.

```sh
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.fsaint.androidagent.LockedFoldedRuntimeAcceptanceTest --no-daemon
```

Expected result:

- The foreground service reaches `isForeground=true`.
- The service notification uses the `agent_runtime` channel, notification id `7101`, and exposes **Stop** and **Restart** actions.
- The notification **Stop** and **Restart** actions change the real foreground service state.
- On a non-secure keyguard, SMS broadcast handling and handler-level notification event translation work after the test asserts `KeyguardManager.isKeyguardLocked`, and cleanup verifies the keyguard is dismissed.
- Telegram transport checkpointing resumes from the persisted update offset after a fresh poller instance.
- A second runtime start does not create duplicate reply work.

Current connected status (2026-08-31): on the secure Android 15/API 35 SM-F711U1, this class completed with four passing tests, zero failures, and the secure-keyguard-sensitive test intentionally skipped. The focused service/recovery/boot run completed 29 tests with zero failures, including a real API 35 `specialUse` foreground-service launch.

This automated check does not physically fold the hinge, validate secure-lock delivery, send carrier SMS from another phone, receive a real notification through Android Notification Access from another app, send a live owner Telegram message, or perform a real force-stop/relaunch proof. Those are manual device steps below.

## Manual folded and locked sequence

1. Install and launch Dark Lord.
2. Grant notification, SMS, phone, microphone, and camera permissions requested by the app.
3. Set Dark Lord as the SMS app and enable notification access.
4. Enter the owner OpenAI API key and Telegram bot credentials, then save the owner Telegram chat id.
5. Open the Android app battery settings for Dark Lord and choose unrestricted battery/background usage. Keep app notifications and the **Agent runtime** channel enabled; boot/sticky restore is intentionally suppressed otherwise.
6. Enable notification access for Dark Lord and choose a test app whose notifications are safe to inspect.
7. Return to Dark Lord and confirm the persistent **Dark Lord background access** notification is visible.
8. Fold the Flip3 and lock the device.
9. Send one Telegram message from the owner chat, one SMS from the owner number, and one visible notification from the chosen test app so Android Notification Access delivery is exercised.
10. Confirm exactly one reply is delivered for the Telegram message and exactly one reply is delivered for the SMS.
11. Unlock the device, open Dark Lord diagnostics, and confirm recent Telegram/SMS/notification runtime or audit evidence is present without exposing message bodies or credentials.
12. Use the persistent notification's **Stop** action and confirm Telegram polling stops.
13. Use the app or notification **Restart** path, send one more owner Telegram message, and confirm one reply.
14. Force-stop Dark Lord, relaunch it explicitly, then send one owner Telegram message and confirm polling resumes without duplicate replies.

Record the device model, Android build, app version, battery mode, notification-access state, SMS-role state, keyguard/folded state, and pass/fail evidence for each message.

## Hard limits

- Android and Samsung policy can still delay, batch, or stop background work under Doze, thermal pressure, low battery, standby buckets, network loss, carrier behavior, or explicit force-stop.
- A user-visible foreground service improves reliability but does not bypass platform restrictions.
- UI actions may require unlock. Folded or locked operation must not assume an activity, display, accessibility surface, browser session, or interactive system dialog is available.
- Camera, microphone, and screen tools are excluded from model context and denied by the tool router for Telegram, SMS, and notification sessions. They are available only from explicit foreground, voice, or capture surfaces. Browser automation remains outside the persistent runtime's continuous responsibilities.
- Notification-listener, SMS, call, and Telegram behavior depends on the corresponding Android role, permission, service access, network availability, and owner/principal configuration.
