# Stage 6 Communications and Principal Administration Design

## Goal

Add the Android SMS, dialer, notification, and owner-administration pathways required for Dark Lord to process communications as scoped agent events on the Samsung SM-F711U1.

## Scope

Stage 6 adds three Android capability modules and application setup/UI wiring:

- `capabilities:sms` becomes a qualifying default SMS application. It receives SMS broadcasts, persists normalized inbound events, sends replies only through the existing scoped tool pathway, and records sent/delivery outcomes truthfully.
- `capabilities:telephony` becomes a qualifying default dialer. It provides a dial-pad activity and a non-null `InCallService` with incoming and ongoing-call UI. It publishes call-state events and supports answer, reject, disconnect, mute, and hold where Telecom reports each action is supported. It never claims two-way PSTN audio capture.
- `capabilities:notifications` supplies a user-enabled `NotificationListenerService`. It emits sanitized notification events only after the listener connection is established.
- The app provides local principal/scope administration and owner-authenticated SMS commands.

No system role changes occur silently. Android role and notification-listener settings are opened only by an explicit local user action. Emergency-call routing remains under Android's preloaded dialer behavior.

## Architecture

Each capability module owns Android-framework adapters and exposes a narrow, testable event port. The app owns Android manifest declarations, role requests, services, and Compose screens. `core:model`, `core:policy`, and `core:runtime` remain Android-UI independent.

```
SMS receiver / InCallService / NotificationListenerService
                       |
                normalized event port
                       |
      principal resolver + scope router (core:policy)
                       |
       durable event/session repositories (core:data)
                       |
             AgentRuntime / escalation flow
                       |
            verified reply or transparent denial
```

### Principal resolution

`PrincipalResolver` normalizes phone numbers with `PhoneNumberUtils.formatNumberToE164` when the active network country is known, falling back to the source string only when Android cannot normalize it. A stored owner E.164 number maps to `OWNER`; an explicitly stored principal maps to `KNOWN`; all other sources map to `UNKNOWN`.

An inbound event is persisted before runtime dispatch and carries its channel (`SMS`, `CALL`, or `NOTIFICATION`), source, normalized principal, and payload. The policy router remains the enforcement point: an UNKNOWN request cannot gain access to a denied tool by planner output or fallback.

### SMS pathway

The manifest supplies the default-SMS role qualification components: SMS-deliver receiver, WAP-push receiver, send-to activity, and respond-via-message service. The module decodes all PDUs supplied by the system, retains the subscription identifier when present, and records one inbound event per SMS message.

Outbound replies use `SmsManager` for the selected subscription. A `SENT` broadcast records transport submission; a `DELIVERED` broadcast records carrier delivery. The synchronous tool result is `UNVERIFIED` until a matching sent/delivery status arrives, and reports a structured failure if the SMS role or runtime permission is absent.

### Dialer pathway

`DialerActivity` handles `ACTION_DIAL` and provides a minimal, accessible dial pad. It delegates outgoing calls to `TelecomManager.placeCall`, including emergency numbers, allowing Android to select the preloaded emergency dialer.

`AgentInCallService` always returns a valid binding and observes every call supplied by Telecom. It emits call-state events and launches the app's incoming/ongoing call UI. `CallController` exposes only operations declared available by the current `Call.Details`; unsupported operations return `UNSUPPORTED`. No class captures, records, or transcribes PSTN media.

### Notification pathway

`AgentNotificationListenerService` is declared with `BIND_NOTIFICATION_LISTENER_SERVICE` and processes notifications only after `onListenerConnected`. It emits package name, notification key, category, post time, and user-visible text fields when present. It does not expose notification content to a principal until the existing scope router permits it.

### Owner administration

`PrincipalSettingsScreen` lists the owner and known principals, allows an owner to add/remove known E.164 numbers, and displays current SMS/dialer/notification access state. All state changes are persisted through the principal repository.

`OwnerSmsCommandHandler` accepts commands only after the sender resolves as `OWNER`. Initial commands are deliberately narrow and explicit:

- `STATUS` returns role and listener state without sensitive event content.
- `KNOWN ADD <E.164>` adds a known principal.
- `KNOWN REMOVE <E.164>` removes a known principal.

Malformed or unauthorized commands are ignored for administration and produce a safe scoped response through the runtime.

## Failure and safety behavior

- Missing SMS/dialer role, permissions, or notification access produces a structured `PERMISSION_REQUIRED` / `OS_RESTRICTED` outcome, never simulated success.
- Inbound events remain durable until the runtime reaches a persisted terminal state.
- Unknown callers/senders can create a bounded escalation but do not receive owner-only data.
- Call-control failures retain the current Telecom state and report the exact failed action.
- SMS transport acceptance is not described as recipient delivery without a delivery report.

## Testing and validation

Unit tests use fake event ports, principal repositories, and call controls to prove sender resolution, scope denial, owner-command authentication, durable event persistence, and unsupported call behavior.

Android instrumentation tests validate manifest-facing SMS and call components without requiring a carrier message or a live call. The physical Flip3 acceptance check then requires the user to grant the SMS role, dialer role, notification access, relevant runtime permissions, and verifies:

1. an inbound SMS produces an OWNER, KNOWN, or UNKNOWN scoped event;
2. an owner command changes the principal list and an unknown sender cannot;
3. an incoming and outgoing carrier call create call-state events and show the app's call UI;
4. a posted test notification creates a notification event after access is granted;
5. the existing escalation flow persists, alerts the owner, resumes, and sends the resulting reply.

## Out of scope

- PSTN audio recording or transcription.
- Call screening, call redirection, RCS implementation, MMS composition, and replacement contact storage.
- Automatic role grants, Device Owner provisioning, and the deferred Flip3 cover-display pathway.
