# Speech recovery verification

## Scope and evidence

Fix the per-press recognizer teardown/reconnect pattern, redundant stop after natural speech completion, and immediate generic failure on a recoverable startup disconnect. Google speech-service lifecycle races remain a hypothesis until repeated physical voice tests pass.

- Regression red: `SpeechRecoveryTest.readinessHasBoundedDeadline` and natural end-of-speech stop assertion failed against the old controller (`/tmp/speech-red.log`).
- Review regression red: cancellation after a transcript did not schedule idle cleanup (`/tmp/speech-idle-red.log`).
- App unit suite passed after fixes (`/tmp/speech-green3.log`). Coverage includes healthy engine reuse, retired callbacks, one safe retry, readiness/partial/speech retry guards, release/cancel/replacement/shutdown during recovery, deadlines, exact-once transcript dispatch, queued-start coalescing, and idle cleanup cancellation/expiry.
- Outer command-order regression: both queued-start/main-cancel and queued-shutdown/main-start tests failed with the command guard removed (`/tmp/speech-command-red.log`) and passed after restoration.
- Whole-project verification: `./gradlew test :app:lintDebug :app:assembleDebug --no-daemon --max-workers=2` succeeded (`/tmp/speech-full-final.log`). XML results: 567 tests, zero failures/errors/skips; lint: zero errors, 63 warnings. Counts include debug/release app variants.
- Scoped code review found and verified fixes for idle cleanup after photo/text interruption and stale background-posted start commands. No remaining actionable findings.
- Installed non-destructively on SM-F711U1 / Android 15 (`:app:installDebug`, `/tmp/speech-install.log`); package update time 2026-09-10 10:22:02 device time. MainActivity launch returned Status ok, 276 ms. Microphone permission remains granted.
- Physical spoken-turn acceptance remains pending operator input. No physical spoken-turn success is claimed from unit tests.

The recognizer is app-owned while the microphone foreground service is per-turn. A healthy inactive binding gets a 30-second idle grace period on normal shutdown or interruption while thinking/responding. Active capture is canceled immediately. This avoids reintroducing per-turn connection teardown through service cleanup.

## Physical acceptance: 20 attempts

After a non-destructive APK upgrade, record attempt number, posture, result, and matching log turn/attempt IDs. Use read-only prompts such as “What is the battery level?” Do not place calls or send messages.

1. Five ordinary unfolded requests, allowing each response to finish.
2. Five folded requests using the configured long-press Assistant action.
3. Five rapid replacement/interruption attempts: interrupt spoken output, restart an active capture, and include a photo-action interruption with operator approval for capture.
4. Five boundary attempts: two silent attempts, one explicit tap/release, one request after at least 30 seconds idle, and one immediate follow-up to that request.

Each accepted utterance must appear exactly once in Outside chat. Canceled or failed attempts must not contribute stale/partial commands. Silent attempts should report no speech, not dispatch a command. No unexplained alternating error-11 failures, duplicate submissions, or microphone remaining active after termination are acceptable. If a reconnect is observed, it must occur at most once and only before readiness/speech; canceling must prevent a later microphone start.

Capture lifecycle logs without transcript text:

```sh
adb logcat -v threadtime -s DarkLordVoice RemoteSpeechRecognitionService RecognitionService
```

The text API cannot exercise microphone recognition or validate audible interruption. Do not substitute successful API replies or injected transcripts for this physical acceptance. No app-data clearing, uninstall, provider change, cloud-transcription fallback, or broad connected-test invocation is required.
