# Background Agent Runtime Specification

## Goal

Keep Dark Lord’s event-driven agent available while the Galaxy Z Flip is folded and locked, subject to Android’s foreground-service and battery-management rules.

## Requirements

1. Telegram polling and queued agent work must run from a user-visible foreground service rather than relying only on the application process.
2. SMS, notification-listener, and in-call integrations remain platform-managed entry points and must continue to work while locked.
3. The service must restart after boot and after process termination where Android permits it, without creating duplicate polling loops.
4. The service must expose a persistent notification with stop/restart actions and clearly state that background agent access is active.
5. The service must not capture microphone, camera, or screen unless an explicit user action starts the existing purpose-specific service.
6. The app must provide a settings shortcut and plain-language guidance for Samsung battery optimization and unrestricted background usage.
7. Locked-screen testing must verify inbound Telegram/SMS/notification events, restart recovery, duplicate prevention, and safe behavior when the device is folded.

## Non-goals

Continuous browser activity, arbitrary UI automation while locked, bypassing Doze/OEM policy, and hidden background operation are out of scope.
