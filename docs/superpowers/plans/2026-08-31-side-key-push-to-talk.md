# Side-Key Push-to-Talk Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Hold the side key, speak, release; Dark Lord answers aloud and on the visible assistant surface using the same agent, tools, MCP servers, and skills as the Telegram chat.

**Architecture:** Keep the OS-owned assistant path (`VoiceInteractionSession.onShow` is "hold"). Add a pure-Kotlin `PushToTalkController` that ends the utterance on the first of session-hide, recognizer end-of-speech, or a surface tap, then dispatches the transcript on the existing `VOICE` channel. The microphone foreground service becomes the turn's lifecycle keeper; a `VoiceResponder` routes `VOICE` replies to a properly initialized TTS speaker and to the cover/open surfaces through one `StateFlow`.

**Tech Stack:** Kotlin, Android `VoiceInteractionSession`, `SpeechRecognizer`, `TextToSpeech`, foreground services, coroutines, Jetpack Compose, JUnit 5 + kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-08-31-side-key-push-to-talk-design.md`

## Global Constraints

- No hardware-key interception anywhere; the side key is observed only through `onShow`/`onHide`.
- The OEM module talks to the app only via string class names and intent actions.
- `core/runtime`, `core/policy` production code, `CommunicationsDispatcher`, `AgentRuntime`, and `AgentSurfaceRegistry` are not modified.
- Logs never include transcript or reply text.

---

### Task 1: Device spike — session and recognizer logging

**Files:**
- Modify: `oem/samsung-flip3/src/main/kotlin/com/fsaint/androidagent/oem/samsungflip3/AgentVoiceInteractionSession.kt`
- Modify: `app/src/main/kotlin/com/fsaint/androidagent/voice/VoiceCaptureService.kt`

- [x] Log `onShow` flags (`SHOW_SOURCE_PUSH_TO_TALK`), arg keys, `invocation_type`, `Intent.EXTRA_TIME` and `onHide` latency under `DarkLordAssist`.
- [x] Log recognizer ready/begin/end-of-speech/error/results-count under `DarkLordVoice`.
- [x] `./gradlew :app:assembleDebug`, install, make Dark Lord the assistant, verify the side-key setting, run the spike protocol from the spec, and record the result in the spec.

### Task 2: Voice turn state and controller (TDD)

**Files:**
- Create: `app/src/main/kotlin/com/fsaint/androidagent/voice/VoiceTurnState.kt`
- Create: `app/src/main/kotlin/com/fsaint/androidagent/voice/PushToTalkController.kt`
- Test: `app/src/test/kotlin/com/fsaint/androidagent/voice/PushToTalkControllerTest.kt`

- [x] Failing tests for every transition in the spec's state table.
- [x] Minimal controller; `./gradlew :app:testDebugUnitTest --tests 'com.fsaint.androidagent.voice.*'`.

### Task 3: Voice responder and TTS speaker

**Files:**
- Create: `app/src/main/kotlin/com/fsaint/androidagent/voice/VoiceResponder.kt`
- Create: `app/src/main/kotlin/com/fsaint/androidagent/voice/AndroidTtsSpeaker.kt`
- Test: `app/src/test/kotlin/com/fsaint/androidagent/voice/VoiceResponderTest.kt`

- [x] `VOICE` replies reach the controller; other channels fall through.
- [x] TTS init gating, language, utterance completion, audio focus, shutdown.

### Task 4: Recognizer port and lifecycle-keeping capture service

**Files:**
- Create: `app/src/main/kotlin/com/fsaint/androidagent/voice/AndroidSpeechRecognizerPort.kt`
- Modify: `app/src/main/kotlin/com/fsaint/androidagent/voice/VoiceCaptureService.kt`

- [x] `ACTION_PRESS` / `ACTION_RELEASE` with intent factories; service observes state, updates notification, stops on `Idle`.
- [x] Remove `SpeechTranscriptBus`.

### Task 5: Application wiring

**Files:**
- Modify: `app/src/main/kotlin/com/fsaint/androidagent/DarkLordApplication.kt`

- [x] Controller + adapters, `TurnDispatcher` with owner check and `VOICE` dispatch, `VoiceResponder`, surfaces bound to the state flow, speaker shutdown.

### Task 6: Assistant surfaces

**Files:**
- Create: `app/src/main/kotlin/com/fsaint/androidagent/ui/AssistantSurface.kt`
- Modify: `app/src/main/kotlin/com/fsaint/androidagent/ui/CoverAssistantScreen.kt`

- [x] Compact cover variant and full open variant; tap-to-send while listening.

### Task 7: Session release signal

**Files:**
- Modify: `oem/samsung-flip3/src/main/kotlin/com/fsaint/androidagent/oem/samsungflip3/AgentVoiceInteractionSession.kt`
- Modify: `oem/samsung-flip3/src/main/kotlin/com/fsaint/androidagent/oem/samsungflip3/AgentVoiceInteractionService.kt`

- [x] `onShow` → PRESS, `onHide` → RELEASE (never `stopService`).

### Task 8: Parity and registration tests

**Files:**
- Modify: `core/policy/src/test/kotlin/com/fsaint/androidagent/policy/ScopedContextCatalogTest.kt`
- Modify: `app/src/androidTest/kotlin/com/fsaint/androidagent/PrototypeAcceptanceTest.kt`

- [x] Owner `VOICE` context is a superset of owner `TELEGRAM` context.
- [x] `VoiceCaptureService` declares the microphone foreground-service type.

### Task 9: Regression

- [x] `./gradlew :app:testDebugUnitTest :core:policy:test :core:runtime:test :app:assembleDebug`.

### Task 10: Docs and device acceptance

**Files:**
- Modify: `docs/getting-started.md`
- Create: `docs/device-test/stage-13-side-key-push-to-talk.md`
- Modify: `docs/acceptance/flip3-prototype-checklist.md`

- [x] Setup steps, manual acceptance scenarios, checklist row 18, spike result recorded in the spec.
