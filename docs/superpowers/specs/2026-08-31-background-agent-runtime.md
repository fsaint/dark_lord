# Background Agent Runtime Specification

## Goal

Keep Dark Lord’s event-driven agent available while the Galaxy Z Flip is folded and locked, subject to Android’s foreground-service and battery-management rules.

## Requirements

1. Telegram polling and queued agent work must run from a user-visible foreground service rather than relying only on the application process.
2. SMS, notification-listener, and in-call integrations remain platform-managed entry points and must continue to work while locked.
3. The service must restart after boot and after process termination where Android permits it, without creating duplicate polling loops.
4. The service must expose a persistent notification with stop/restart actions and clearly state that background agent access is active.
5. Background Telegram, SMS, and notification sessions must neither receive nor execute microphone, camera, or screen tools. Those tools are available only from explicit foreground, voice, or capture surfaces.
6. The app must provide a settings shortcut and plain-language guidance for Samsung battery optimization and unrestricted background usage.
7. Locked-screen testing must verify inbound Telegram/SMS/notification events, restart recovery, duplicate prevention, and safe behavior when the device is folded.
8. Boot, sticky, and explicit runtime starts must not operate without a visible notification. If app notification permission or the runtime channel is disabled, restoration is skipped and the app exposes a notification-settings action.

## Non-goals

Continuous browser activity, arbitrary UI automation while locked, bypassing Doze/OEM policy, and hidden background operation are out of scope.
