# Stage 12 background runtime device checklist

Use this checklist on the Samsung Galaxy Z Flip3 running Android 15 after installing a current debug or release build of Dark Lord. The goal is to prove the visible foreground service keeps event-driven agent work reachable while the phone is folded and locked, within Android and OEM policy limits.

## Automated acceptance check

Run the connected instrumentation acceptance test:

```sh
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.fsaint.androidagent.LockedFoldedRuntimeAcceptanceTest --no-daemon
```

Expected result:

- The foreground service reaches `isForeground=true`.
- The service notification uses the `agent_runtime` channel, notification id `7101`, and exposes **Stop** and **Restart** actions.
- SMS and notification-listener events are accepted through their Android entry points while the keyguard is locked.
- Telegram polling resumes from the persisted update offset after a fresh service instance, matching the force-stop/relaunch recovery boundary.
- A second runtime start does not create duplicate reply work.

## Manual folded and locked sequence

1. Install and launch Dark Lord.
2. Grant notification, SMS, phone, microphone, and camera permissions requested by the app.
3. Set Dark Lord as the SMS app and enable notification access.
4. Enter the owner OpenAI API key and Telegram bot credentials, then save the owner Telegram chat id.
5. Open the Android app battery settings for Dark Lord and choose unrestricted battery/background usage. Keep Dark Lord notifications enabled.
6. Return to Dark Lord and confirm the persistent **Dark Lord background access** notification is visible.
7. Fold the Flip3 and lock the device.
8. Send one Telegram message from the owner chat and one SMS from the owner number.
9. Confirm exactly one reply is delivered for the Telegram message and exactly one reply is delivered for the SMS.
10. Unlock the device, open Dark Lord diagnostics, and confirm recent runtime/audit evidence is present without exposing message bodies or credentials.
11. Use the persistent notification's **Stop** action and confirm Telegram polling stops.
12. Use the app or notification **Restart** path, send one more owner Telegram message, and confirm one reply.

Record the device model, Android build, app version, battery mode, notification-access state, SMS-role state, and pass/fail evidence for each message.

## Hard limits

- Android and Samsung policy can still delay, batch, or stop background work under Doze, thermal pressure, low battery, standby buckets, network loss, carrier behavior, or explicit force-stop.
- A user-visible foreground service improves reliability but does not bypass platform restrictions.
- UI actions may require unlock. Folded or locked operation must not assume an activity, display, accessibility surface, browser session, or interactive system dialog is available.
- Browser, microphone, camera, and screen capture are on-demand operations. They are not continuous background operations and are not started by the persistent runtime service.
- Notification-listener, SMS, call, and Telegram behavior depends on the corresponding Android role, permission, service access, network availability, and owner/principal configuration.
