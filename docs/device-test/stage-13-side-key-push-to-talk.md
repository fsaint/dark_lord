# Stage 13 side-key push-to-talk device checklist

Use this checklist on the Samsung Galaxy Z Flip3 running Android 15 (One UI 7) after installing a current debug build of Dark Lord. The goal is to prove that holding the side key starts listening immediately, that the utterance ends on release (or as soon as speech stops), and that the reply is spoken and shown on the visible assistant surface through the same agent runtime as the Telegram chat.

Design reference: `docs/superpowers/specs/2026-08-31-side-key-push-to-talk-design.md`.

## Automated checks

```sh
./gradlew :app:testDebugUnitTest --tests 'com.fsaint.androidagent.voice.*'
./gradlew :core:policy:test --tests '*ScopedContextCatalogTest*'
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.fsaint.androidagent.PrototypeAcceptanceTest --no-daemon
```

Expected result:

- `PushToTalkControllerTest` covers every state transition: press, the three interchangeable end-of-utterance signals, finalize and reply timeouts, no owner, recognizer errors, barge-in, and late replies.
- `VoiceResponderTest` proves `VOICE` replies reach the turn and other channels are untouched.
- `voiceOwnerContextIsSupersetOfTelegramOwnerContext` proves the owner voice session receives every tool, MCP server, and skill the Telegram session receives.
- `PrototypeAcceptanceTest` proves `VoiceCaptureService` is registered as a microphone foreground service.

## Setup

1. Install and launch Dark Lord; grant the microphone permission when prompted.
2. Provision the owner and save the OpenAI API key.
3. Tap **Make Dark Lord your Assistant** and accept the role prompt. Confirm: `adb shell settings get secure assistant` prints `com.fsaint.androidagent/com.fsaint.androidagent.oem.samsungflip3.AgentVoiceInteractionService`.
4. Open Settings › Advanced features › Side button › **Press and hold** and choose the digital assistant. Confirm: `adb shell settings get global function_key_config_longpress_selected_item` prints `long_press_ai_agent_app`.
5. Start a log capture: `adb logcat -c && adb logcat -s DarkLordAssist DarkLordVoice DarkLordOpenAI`.

## Spike: what the OS sends on release

Hold the side key for about three seconds while saying a sentence, then release. Repeat three times folded and three times open. For each attempt record from the log:

- `onShow flags=… pushToTalk=…` — whether `SHOW_SOURCE_PUSH_TO_TALK` is set.
- Whether `onHide sinceShowMs=…` appears at the moment of release, and how many milliseconds after the hold began.
- Whether `onEndOfSpeech` precedes `onHide`.

Write the outcome into the spec's "Spike protocol" section. If One UI hides the session on release, the release is the usual end-of-utterance signal; otherwise the ~1 s end-of-speech is.

## Manual sequence

| # | Step | Expected |
|---:|---|---|
| 1 | Fold the phone. Hold the side key, ask `What is my battery level?`, release. | Cover display shows **Listening…** immediately, then **Thinking…**, then the answer. The answer is spoken. Log shows `press`, `onEndOfSpeech` or `onHide`, `onResults count=…`, `Responses API HTTP status=200`, `turn complete; stopping`. |
| 2 | Open the phone. Repeat step 1. | Main screen shows the same sequence with a **Tap to send** hint while listening. |
| 3 | Hold, start a long sentence, tap the surface mid-sentence. | Capture finalizes at the tap; the reply covers what was said before it. |
| 4 | Hold and release without speaking. | Within ~3 s Dark Lord says "I didn't catch that." and the surface shows the same text. |
| 5 | Send the same battery question from the owner Telegram chat. | Same tool (`device.battery`) answers both; the spoken answer and the Telegram answer agree. |
| 6 | Hold during a long spoken reply. | Speech stops and a new listening turn starts. |
| 7 | Revoke the microphone permission, hold the side key. | Dark Lord speaks and shows the microphone-permission prompt; no crash. |
| 8 | Clear the owner (fresh install), hold the side key and speak. | Dark Lord speaks "Set up an owner in Dark Lord first." |
| 9 | Enable airplane mode, hold and speak. | The runtime's provider-error sentence is spoken and shown; the turn ends normally. |
| 10 | Play music, then hold and ask a question. | Music ducks while Dark Lord speaks and resumes afterwards. |

Record the device model, Android build, app version, folded/open state, assistant-role state, and pass/fail evidence for each step. Never paste transcript or reply text from logs; the app does not log them.

Current connected status (2026-08-31, SM-F711U1, Android 15 / One UI 7): the assistant role was granted with `cmd role add-role-holder`, and an injected side-key long press (`adb shell input keyevent --longpress KEYCODE_POWER`) produced `onShow flags=0x7 invocationType=6`, `press`, `onReadyForSpeech`, `onError code=7` after ~5 s of silence, the spoken "I didn't catch that", and `turn complete; stopping`. `onHide` did not fire on release. Steps 1–10 with real speech remain manual and NOT_RUN.

## Hard limits

- Android never delivers the side (power) key to apps; only the assistant show/hide lifecycle is observable. A literal hold-to-release exists only if One UI hides the session on release.
- Launching from the keyguard is not declared; unlock the phone first.
- Samsung's speech recognizer may ignore the silence-length and segmented-session extras; the 3 s finalize timeout bounds the wait.
