# Side-Key Push-to-Talk Specification

## Goal

Let the owner hold the Galaxy Z Flip 3 side (power) key, speak, release, and have Dark Lord answer — spoken aloud and shown on the cover display when folded or on the main screen when open — through the same agent runtime, tools, MCP servers, and skills that serve the Telegram chat.

## Platform facts

1. Android never delivers `KEYCODE_POWER` to applications or accessibility services. `PhoneWindowManager.interceptKeyBeforeQueueing` clears `ACTION_PASS_TO_USER` for the power key, and `AccessibilityInputFilter` returns early for any key without `FLAG_PASS_TO_USER`. No key-up event for the side key is observable by an app.
2. On a long press the OS launches the digital assistant through `VoiceInteractionSession.onShow(args, showFlags)`. `showFlags` may carry `SHOW_SOURCE_PUSH_TO_TALK` (`1 shl 5`); `args` may carry `invocation_type` and `Intent.EXTRA_TIME`. AOSP sends nothing on release.
3. One UI 7 (Android 15) routes the side-key long press to the app that holds `RoleManager.ROLE_ASSISTANT` when the side-key setting is "AI agent app" (`function_key_config_longpress_selected_item=long_press_ai_agent_app`). Whether One UI hides the session when the key is released is unknown until measured on the device (section 6).
4. Dark Lord already registers `AgentVoiceInteractionService`, `AgentVoiceInteractionSessionService`, and `AgentVoiceInteractionSession` (OEM module), a microphone foreground service (`VoiceCaptureService`), a `VOICE` dispatch channel, and cover/open Compose surfaces via `AgentSurfaceRegistry`.

## Requirements

1. No hardware-key interception. "Hold" is exactly `VoiceInteractionSession.onShow`; nothing else may observe or consume the side key.
2. Listening starts immediately on `onShow`, before any UI is rendered, on both the cover display (folded) and the main screen (open).
3. End of utterance is the first of three signals: (a) the session hiding (`onHide`) if One UI hides it on release, (b) the recognizer's end-of-speech (~1 s of silence), (c) a tap on the assistant surface while listening. All three are handled identically; later signals are no-ops.
4. Capture outlives the session. Hiding the session must not stop the microphone service or discard a transcript that is still being finalized.
5. The transcript is dispatched as the existing `voice.transcript` event on the `VOICE` channel for the owner principal, so the turn runs through `AgentRuntime` and `ConversationHarness` with the same tool, MCP, and skill catalogs as the owner's Telegram session. The owner `VOICE` context must remain a superset of the owner `TELEGRAM` context.
6. The reply is always spoken (properly initialized TTS with language, audio focus, and completion callback) and shown on whichever assistant surface is visible. If the session has already hidden, the reply is still spoken and its text is placed in the microphone service's notification.
7. Failures are spoken and shown, then the turn resets: no speech recognized, recognizer error, no owner configured, microphone permission missing, and reply timeout. A late reply after a timeout is still spoken.
8. A new press while a reply is being spoken interrupts speech and starts a new turn. Presses while listening, finalizing, or thinking are ignored.
9. Logs carry timing and state only — never transcript or reply text.

## Non-goals

Launching from the keyguard (`supportsLaunchVoiceAssistFromKeyguard`), a wake word, streaming or partial-transcript display, streaming TTS, an in-app text chat, and any change to the Telegram, SMS, notification, or call channels.

## Architecture

New code lives in `app/src/main/kotlin/com/fsaint/androidagent/voice/` unless noted. `core/runtime`, `core/policy`, `CommunicationsDispatcher`, `AgentRuntime`, and `AgentSurfaceRegistry` are unchanged. The OEM module continues to reach the app only through string class names and intent actions.

| Component | Responsibility |
|---|---|
| `VoiceTurnState` | Sealed state: `Idle`, `Listening`, `Finalizing`, `Thinking`, `Responding(text)`, `Error(reason)`; `VoiceTurnError { NO_SPEECH, RECOGNIZER, NO_OWNER, MICROPHONE_PERMISSION, TIMEOUT }`, each with a spoken message. Exposed as a `StateFlow`. |
| `PushToTalkController` | Pure-Kotlin state machine over ports `RecognizerPort` (`startListening`, `stopListening`, `cancel`), `TurnDispatcher` (`suspend (String) -> Boolean`; `false` means no owner), and `Speaker` (`speak(text, onDone)`, `stop`, `shutdown`). Inputs: `pressed`, `released`, `endOfSpeech`, `transcript`, `tapToSend`, `replyReady`, `speechDone`, `fail`. Finalize timeout 3 s; reply timeout 60 s. |
| `AndroidSpeechRecognizerPort` | Wraps `SpeechRecognizer` with the existing recognition intent, marshals to the main looper, maps `ERROR_NO_MATCH`/`ERROR_SPEECH_TIMEOUT` to `NO_SPEECH` and other errors to `RECOGNIZER`. |
| `AndroidTtsSpeaker` | `TextToSpeech` with init gating, `Locale.getDefault()`, `UtteranceProgressListener`, transient-may-duck audio focus with `USAGE_ASSISTANT`, unique utterance ids, `shutdown()`. Init or language failure completes the utterance immediately so the controller never hangs. |
| `VoiceResponder` | `ReplySender` that routes channel `VOICE` to `controller.replyReady(text)` and every other channel to the existing sender. |
| `VoiceCaptureService` | Microphone foreground service acting as lifecycle keeper: `ACTION_PRESS` checks `RECORD_AUDIO`, calls `startForeground`, and forwards `pressed()`; `ACTION_RELEASE` forwards `released()`. It observes the state flow to update its notification with the reply and stops itself once the turn returns to `Idle`. |
| `AgentVoiceInteractionSession` | `onShow` logs the show flags and starts the service with `ACTION_PRESS`; `onHide` logs and starts the service with `ACTION_RELEASE` instead of stopping it. |
| `AssistantSurface` | Compose surface rendering the state; a tap while `Listening` sends. `CoverAssistantScreen` is the compact variant; `OpenAssistantSurface` is the full-screen variant. The settings screen (`OpenAssistantScreen`) stays on `MainActivity`. |
| `DarkLordApplication` | Owns the controller and adapters, implements `TurnDispatcher` with the existing owner check and `VOICE` dispatch, installs `VoiceResponder`, feeds the state flow to both surfaces, and shuts the speaker down. |

### State transitions

| From | Input | To | Side effects |
|---|---|---|---|
| Idle / Error | pressed | Listening | `recognizer.startListening()` |
| Responding | pressed | Listening | `speaker.stop()`, `startListening()` |
| Listening / Finalizing / Thinking | pressed | unchanged | none |
| Listening | released / endOfSpeech / tapToSend | Finalizing | `stopListening()`, finalize timer |
| Finalizing | released / endOfSpeech / tapToSend | unchanged | none |
| Listening / Finalizing | transcript (non-blank) | Thinking | `turns.dispatch(text)`, reply timer |
| Listening / Finalizing | transcript (blank) or finalize timeout | Error(NO_SPEECH) | `cancel()`, spoken message |
| Thinking | dispatch returns false | Error(NO_OWNER) | spoken message |
| Thinking | reply timeout | Error(TIMEOUT) | spoken message |
| any | replyReady(text) | Responding(text) | `speaker.speak(text)` |
| Responding / Error | speechDone | Idle | none |
| Listening / Finalizing | fail(RECOGNIZER / NO_SPEECH) | Error(reason) | spoken message |
| any | fail(MICROPHONE_PERMISSION) | Error | spoken message |
| Thinking / Responding | released | unchanged | none |

## Spike protocol

Before the controller is wired, the session logs `onShow` (flags, argument keys, `invocation_type`, `EXTRA_TIME`) and `onHide` (milliseconds since show) under the `DarkLordAssist` tag, and the capture service logs recognizer lifecycle under `DarkLordVoice`. With Dark Lord holding the assistant role and the side key set to the digital assistant, hold the key for about three seconds while speaking, release, and repeat three times folded and three times open. Record here:

- One UI 7 fires `onHide` on side-key release: **no** (2026-08-31, SM-F711U1, Android 15 / One UI 7, injected `input keyevent --longpress KEYCODE_POWER`; the session stayed shown for the whole turn).
- Latency from release to `onHide`: not applicable.
- `SHOW_SOURCE_PUSH_TO_TALK` present in `showFlags`: **no** — `showFlags=0x7` (`SHOW_WITH_ASSIST | SHOW_WITH_SCREENSHOT | SHOW_SOURCE_ASSIST_GESTURE`) with `invocation_type=6` (`INVOCATION_TYPE_POWER_BUTTON_LONG_PRESS`), i.e. One UI routes the side key through the standard AOSP assistant path.
- `onEndOfSpeech` observed before `onHide`: `onHide` never fired; with no speech the recognizer reported `ERROR_NO_MATCH` (~5 s) and the turn ended through the `NO_SPEECH` path with the spoken message.

Consequence: the recognizer's end of speech is the primary end-of-utterance signal on this device; the hide and tap signals remain as fallbacks. A physical hold-and-release measurement with real speech is still pending in the Stage 13 manual sequence.

## Test strategy

- JVM: `PushToTalkControllerTest` covers every transition in the table, including hide-before-transcript, blank transcript, both timeouts, the late reply, and barge-in. `VoiceResponderTest` covers channel routing.
- Policy: `ScopedContextCatalogTest.voiceOwnerContextIsSupersetOfTelegramOwnerContext` guards requirement 5.
- Instrumentation: `PrototypeAcceptanceTest` asserts `VoiceCaptureService` declares the microphone foreground-service type.
- Device: `docs/device-test/stage-13-side-key-push-to-talk.md` records the manual acceptance run.

## Open questions

- The `ReplySender` in `DarkLordApplication` still routes unknown channels to SMS. This spec does not change that behavior.
- If One UI routes the side key somewhere other than the AOSP assistant path, the `ACTION_ASSIST` activity filter on `MainActivity` is the fallback entry point; it is not wired in this iteration.
