# Stage 6 communications device acceptance

Use this checklist on the dedicated Samsung Galaxy Z Flip3 (`SM-F711U1`) running Android 15.  It is deliberately a physical-device checklist: do not substitute carrier traffic, role assignment, or notification-listener access with `adb` commands.  In particular, do not use `cmd role` or `pm grant` to bypass a system decision, and do not send an SMS or place a call from the test workstation.

## Recorded baseline

Recorded on 2026-08-29 after installing `app-debug.apk` version `0.1.0` (version code 1):

- The connected device identifies as `SM-F711U1`, Android 15.
- The current SMS role holder is `com.google.android.apps.messaging` (Google Messages).
- The current dialer role holder is `com.samsung.android.dialer` (Samsung Dialer).
- Dark Lord is installed at `com.fsaint.androidagent`; its SMS, call, and notification runtime permissions are denied, and it is absent from `enabled_notification_listeners`.

This is a read-only baseline, not a failure.  A tester must make every role, permission, and notification-access decision below on the device.

## Grant the communications capabilities

1. Open **Dark Lord**.  Open **Principal administration** and choose **Request SMS and dialer roles**.
2. Accept the Android role prompt **“Set Dark Lord as your default SMS app?”**.  The role changes the default SMS app; it does not send a message.
3. Accept the following Android role prompt, **“Set Dark Lord as your default Phone app?”**.  The role changes the default dialer; it does not place a call.
4. In **Principal administration**, choose **Grant SMS and call permissions**.  Accept the Android permission prompts:
   - **“Allow Dark Lord to send and view SMS messages?”** — choose **Allow**.
   - **“Allow Dark Lord to make and manage phone calls?”** — choose **Allow**.
   - **“Allow Dark Lord to send you notifications?”** — choose **Allow**.
5. Choose **Open notification access settings**.  In **Device & app notifications → Notification access**, turn on **Dark Lord** and accept **“Allow Dark Lord to access your notifications?”**.
6. Return to the app, choose **Refresh access status**, and record that SMS default app, dialer default app, notification access, notification permission, and SMS/call permissions are all **Granted**.

The wording above is the Android 15 English system text expected for this build.  Samsung may add a title or safety explanation around the quoted text; do not proceed if the target app is not **Dark Lord**.  There is no automatic approval path.

### Stop and restore the normal apps

If the tester stops at any point, restore both defaults before handing the device back:

1. Go to **Settings → Apps → Choose default apps → SMS app** and choose **Messages** (`com.google.android.apps.messaging`).
2. Go to **Settings → Apps → Choose default apps → Phone app** and choose **Phone** (`com.samsung.android.dialer`).
3. Go to **Settings → Notifications → Device & app notifications → Notification access**, turn **Dark Lord** off, and confirm the revocation.
4. In **Settings → Apps → Dark Lord → Permissions**, revoke the SMS, call, and notification permissions granted for this test if the test device is returning to its baseline.
5. Confirm the two default selections with the settings UI (or read-only `adb shell cmd role get-role-holders`); do not switch them with `adb`.

## Manual acceptance record

Precondition: an owner E.164 number must already be provisioned as an `OWNER` principal through an approved provisioning process. The Stage 6 settings UI can add `KNOWN` principals but does not expose an action to establish the initial `OWNER`; do not insert one through `adb` or by editing app data. If approved owner provisioning is unavailable, the owner-command and escalation rows are blocked. Record only test numbers and redacted event IDs; never put message contents, contacts, or PSTN captures in this file.

| Check | Tester action | Required observation | Result / evidence |
| --- | --- | --- | --- |
| Owner inbound SMS | From the provisioned owner phone, send a harmless test message to the Flip3. | The received event is scoped to an `OWNER` session and has an audited event ID. | Not performed — requires tester action and a pre-provisioned owner. |
| Unknown inbound SMS | From a second, non-principal phone, send a harmless test message. | An `UNKNOWN` session/event is recorded. No owner-only status, principal list, or other owner data is returned. | Not performed — requires tester action. |
| Owner administration | From the owner phone send `KNOWN ADD +<test-number>` using a real E.164 test number. Refresh **Known principals**. | The command reply confirms the addition and the local list shows the E.164 number as `KNOWN`; its command event is persisted and audited before the reply. | Not performed — requires tester action. |
| Incoming call | From a second device, place a non-emergency call to the Flip3; answer/end it manually. | Dark Lord’s incoming-call state and call UI appear; call events are persisted/audited. | Not performed — requires tester action. |
| Outgoing call | From the Flip3, manually place and end a non-emergency call to a test number. | Dark Lord’s outgoing-call state and call UI appear; call events are persisted/audited. | Not performed — requires tester action. |
| Notification intake | Post a benign notification from an installed test app after notification access is enabled. | A `notification.posted` event with the posting package source is generated and persisted/audited. | Not performed — requires tester action. |
| Known-person escalation/resumption | Trigger a benign event from the newly known number that needs owner review, then reply to the owner escalation through the approved owner flow. | The open escalation remains persisted across process restart, resolves after the owner reply, resumes the associated session, and delivers the resulting reply. | Not performed — requires tester action. |

For each completed row, capture the timestamp, redacted event/audit ID, session scope, and the result in the final column.  Do not use `adb` to fabricate carrier events.  Carrier-dependent behavior is not automated: connected tests must express it with `Assume` and report it as skipped.

## Evidence collection

After each manual action, use the app’s diagnostics screen if it is present in the installed build, or collect a read-only scoped log view such as:

```sh
adb logcat -d -v threadtime | rg 'Dark Lord|com\.fsaint\.androidagent'
```

Record only the event/audit identifiers and disposition.  If no diagnostics or scoped log entry is exposed, mark the row **Blocked: no observable persisted/audited event surface**; do not infer acceptance from a notification or call UI alone.

## Automated evidence

`./gradlew test lint connectedCheck` completed on 2026-08-29 with exit code 0 in 1m 9s.  It ran against the connected Flip3; 2 app, 4 notification, 2 telephony, and 4 OEM connected tests completed.  `physicalFlip3ExposesIts512x260PresentationDisplay` was reported **SKIPPED**, which is expected for hardware/display availability and is not carrier verification.
