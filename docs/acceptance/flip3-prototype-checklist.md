# Dark Lord Flip3 prototype acceptance checklist

This checklist is the traceability record for the 28 scenarios in `SPEC.md` §64. It deliberately distinguishes automated evidence from checks that require a real phone, permissions, carrier, or external service. Do not mark a scenario passed from a unit test when the requirement is device or network dependent.

## Evidence record

Copy this record for every execution:

```text
timestamp_utc: 2026-__-__T__:__:__Z
commit: <git SHA>
apk_sha256: <SHA-256 or n/a>
device_serial: <adb serial>
device_model_android: <model / Android version>
scenario: <01-28>
disposition: PASS | FAIL | BLOCKED | UNSUPPORTED | NOT_RUN
evidence: <test command, screenshot/log path, or manual observation>
audit_ids: <IDs, or n/a>
notes: <permission, network, carrier, or limitation>
```

## Scenarios

Initial disposition is `NOT_RUN` until a record above is attached. Automated rows identify regression coverage; manual rows require the SM-F711U1 and must be recorded with device evidence.

| ID | Acceptance scenario | Evidence path | Disposition |
|---:|---|---|---|
| 01 | Launch application | `app` instrumentation / launcher | NOT_RUN |
| 02 | Inspect accessibility tree | Accessibility capability unit + enabled-service device check | NOT_RUN |
| 03 | Click application control | Accessibility action unit + manual target app | NOT_RUN |
| 04 | Enter text | Accessibility action unit + manual target app | NOT_RUN |
| 05 | Capture screenshot | Screen capability unit + MediaProjection consent device check | NOT_RUN |
| 06 | Scan Wi-Fi | Radios capability unit + device scan | NOT_RUN |
| 07 | Scan Bluetooth | Radios capability unit + device scan | NOT_RUN |
| 08 | Connect Bluetooth peripheral | Radios capability unit + paired peripheral | NOT_RUN |
| 09 | Capture photo | Camera capability unit + camera device check | NOT_RUN |
| 10 | Record microphone | Microphone capability unit + runtime permission device check | NOT_RUN |
| 11 | Receive notification event | Notifications instrumentation/unit + posted notification | NOT_RUN |
| 12 | Receive SMS | SMS instrumentation + active SIM/carrier test | NOT_RUN |
| 13 | Resolve sender scope | Policy and communications unit tests | NOT_RUN |
| 14 | Respond automatically | Runtime/communications tests + owner SMS device check | NOT_RUN |
| 15 | Receive call | Telephony instrumentation + carrier call | NOT_RUN |
| 16 | Resolve caller scope | Policy/telephony unit tests + caller device check | NOT_RUN |
| 17 | Answer, reject, and hang up | Telephony capability tests + carrier call | NOT_RUN |
| 18 | Operate with screen off | Stage 13 side-key push-to-talk manual device check (cover surface + spoken reply) | NOT_RUN |
| 19 | Recover after reboot | `BootRecoveryTest` + physical reboot and WorkManager observation | NOT_RUN |
| 20 | Connect external MCP | MCP unit tests + configured HTTPS endpoint | NOT_RUN |
| 21 | Execute skill | Skills unit tests + approved declarative skill device check | NOT_RUN |
| 22 | Update skill | Skill rollback/update tests + staged package device check | NOT_RUN |
| 23 | Create owner sub-agent | Runtime/policy tests + owner flow device check | NOT_RUN |
| 24 | Create known sub-agent | Runtime/policy tests + enrolled-principal device check | NOT_RUN |
| 25 | Create unknown sub-agent | Runtime/policy tests + unknown-principal device check | NOT_RUN |
| 26 | Enforce scope denial | `ScopedToolRouterTest`, `ScopedMcpRouterTest`, capability denial tests | NOT_RUN |
| 27 | Escalate known-agent question to owner | Escalation/runtime tests + owner notification device check | NOT_RUN |
| 28 | Resume conversation after owner response | Escalation/runtime tests + end-to-end owner response device check | NOT_RUN |

## Required posture and boundary checks

Record separate evidence for Assistant invocation with the phone open and closed, cover touch, spoken request/response, permission refusal, and OAuth/MCP failure. Record `UNSUPPORTED`, `PERMISSION_REQUIRED`, `SCOPE_DENIED`, or `NETWORK_ERROR` where the platform or deployment genuinely prevents the scenario; never bypass a system prompt with `pm grant` or role commands.

## Release gate

Before declaring the prototype accepted, attach the output of the focused Gradle tests/lint, the connected test invocation, the release APK SHA-256, and the evidence records for all 28 rows. A `PASS` claim requires the evidence to match the scenario's path and device/network scope.

## 2026-08-30 automated release evidence

```text
commit: 3c9aa5c (release build), followed by 835e638 (acceptance smoke test) and the final test-fix commit
apk: app/build/outputs/apk/release/app-release.apk
apk_sha256: f6ffebd9450dd2419acb7eb8468fb0f1a05af5c8cbbf2d4605a578ae543de21d
device_serial: R5CRB0N64WH
device_model_android: SM-F711U1 / Android 15
release_gate: ./gradlew test lintDebug connectedCheck assembleRelease releaseSha256
release_gate_result: PASS
connected_result: 15/15 app tests passed
install_result: PASS (adb install -r)
```

This automated record does not mark carrier, external MCP/Tailscale, or posture/voice scenarios as passed; those remain manual rows requiring operator evidence.
