# Stage 12 background runtime device checklist

Use this checklist on the Samsung Galaxy Z Flip3 running Android 15 after installing a current debug or release build of Dark Lord. The goal is to prove the visible foreground service keeps event-driven agent work reachable while the phone is folded and locked, within Android and OEM policy limits.

## Automated acceptance check

Run this connected instrumentation check from an unlocked device. The test starts the foreground runtime, verifies notification controls, then puts the device into keyguard lock for the SMS broadcast and handler-level notification translation checks. On a secure-lock device, an operator may need to unlock the phone after the test before running other Compose/UI instrumentation.

```sh
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.fsaint.androidagent.LockedFoldedRuntimeAcceptanceTest --no-daemon
```

Expected result:

- The foreground service reaches `isForeground=true`.
- The service notification uses the `agent_runtime` channel, notification id `7101`, and exposes **Stop** and **Restart** actions.
- The notification **Stop** and **Restart** actions change the real foreground service state.
- SMS broadcast handling and handler-level notification event translation work after the test asserts `KeyguardManager.isKeyguardLocked`.
- Telegram transport checkpointing resumes from the persisted update offset after a fresh poller instance.
- A second runtime start does not create duplicate reply work.

This automated check does not physically fold the hinge, send carrier SMS from another phone, receive a real notification through Android Notification Access from another app, send a live owner Telegram message, or perform a real force-stop/relaunch proof. Those are manual device steps below.

## Manual folded and locked sequence

1. Install and launch Dark Lord.
2. Grant notification, SMS, phone, microphone, and camera permissions requested by the app.
3. Set Dark Lord as the SMS app and enable notification access.
4. Enter the owner OpenAI API key and Telegram bot credentials, then save the owner Telegram chat id.
5. Open the Android app battery settings for Dark Lord and choose unrestricted battery/background usage. Keep Dark Lord notifications enabled.
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
- Browser, microphone, camera, and screen capture are on-demand operations. They are not continuous background operations and are not started by the persistent runtime service.
- Notification-listener, SMS, call, and Telegram behavior depends on the corresponding Android role, permission, service access, network availability, and owner/principal configuration.
