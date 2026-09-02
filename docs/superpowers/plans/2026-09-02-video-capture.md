# Video Capture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add reliable owner-scoped video recording that runs in the background, produces bounded MP4 artifacts, and can be delivered through supported channels.

**Architecture:** Extend the camera capability with a `MediaRecorder`-backed adapter and a foreground media service. The existing unified `BackgroundJobManager` owns lifecycle, persistence, resource exclusivity, artifact creation, and cancellation; the conversational model receives only artifact metadata. Camera operations remain permission- and owner-scoped below the model.

**Tech Stack:** Kotlin, Android Camera2, `MediaRecorder`, coroutines, foreground services, existing `ArtifactStore`, Room-independent job persistence, local chat API, Telegram transport, JUnit/Kotlin tests, connected Android device.

**Spec:** `docs/superpowers/specs/2026-08-29-android-agent-design.md` and the background-job requirements captured in the implementation conversation.

## Global Constraints

- Minimum Android API remains 31; compile SDK remains 35.
- Video output is MP4 using H.264 video and AAC audio where the device profile supports both.
- The model receives opaque artifact IDs and metadata, never filesystem paths or raw media bytes.
- Video jobs are owner-only, visibly notified, bounded by duration and byte limits, and never silently restarted after process death.
- Camera and microphone are exclusive resources; overlapping jobs return `DEVICE_BUSY`.
- SMS and voice responses remain text-only; Telegram and the local API may expose binary artifacts.
- All tokens, credentials, and media contents remain excluded from logs and diagnostics.

### Task 1: Define the video adapter contract

**Files:**
- Modify: `capabilities/camera/src/main/kotlin/com/fsaint/androidagent/capabilities/camera/CameraCapability.kt`
- Test: `capabilities/camera/src/test/kotlin/com/fsaint/androidagent/capabilities/camera/CameraCapabilityTest.kt`

**Interfaces:**
- Produce `VideoStartRequest(cameraId: String?, maxWidth: Int, maxHeight: Int, maxDurationMs: Long, maxBytes: Int)`.
- Produce `VideoClip(file: File, mimeType: String, width: Int, height: Int, durationMs: Long)`.
- Extend `CameraAdapter` with `suspend fun startVideo(request: VideoStartRequest): CameraOperationOutcome`, `suspend fun stopVideo(): CameraVideoStopOutcome`, and `fun recordingVideo(): Boolean`.
- Replace `camera.startVideo` and `camera.stopVideo` unsupported handlers with capability calls returning typed results.

- [ ] **Step 1: Write failing fake-adapter tests** for start success, stop returning a `VideoClip`, permission denial, unsupported camera, and busy camera.
- [ ] **Step 2: Run** `./gradlew :capabilities:camera:test` and verify the new tests fail because the contract and handlers do not exist.
- [ ] **Step 3: Add the data classes, sealed stop outcome, interface methods, and capability handler mapping.** Enforce `1..600_000` ms duration, positive dimensions, and a bounded byte limit.
- [ ] **Step 4: Run** `./gradlew :capabilities:camera:test` and verify all contract tests pass.
- [ ] **Step 5: Commit** `git add capabilities/camera && git commit -m "feat: define camera video capability contract"`.

### Task 2: Implement Camera2 and MediaRecorder

**Files:**
- Modify: `capabilities/camera/src/main/kotlin/com/fsaint/androidagent/capabilities/camera/AndroidCameraAdapter.kt`
- Create: `capabilities/camera/src/main/kotlin/com/fsaint/androidagent/capabilities/camera/VideoRecorderSession.kt`
- Test: `capabilities/camera/src/androidTest/kotlin/com/fsaint/androidagent/capabilities/camera/AndroidCameraVideoTest.kt`

**Interfaces:**
- `VideoRecorderSession.start(camera: CameraDevice, surface: Surface)` configures `MediaRecorder` with `OutputFormat.MPEG_4`, `VideoEncoder.H264`, `AudioEncoder.AAC`, the selected size, and a private files-directory output file.
- `VideoRecorderSession.stop(): VideoClip` stops and releases recorder/session resources and reports actual duration and file size.

- [ ] **Step 1: Add a JVM-testable recorder configuration test** asserting MP4 output, H.264/AAC codecs, configured size, and max-duration clamp.
- [ ] **Step 2: Run** the camera tests and verify the new configuration test fails.
- [ ] **Step 3: Implement camera selection and supported-size negotiation** using `SCALER_STREAM_CONFIGURATION_MAP` video sizes, preferring back camera and a safe 1280x720 profile.
- [ ] **Step 4: Implement `MediaRecorder` lifecycle** with a `HandlerThread`, `CameraCaptureSession`, timeout, `SecurityException`, `CameraAccessException`, and `RuntimeException` mapping to existing outcomes.
- [ ] **Step 5: Enforce byte and duration bounds** by stopping at the earlier limit and deleting incomplete zero-byte files.
- [ ] **Step 6: Run** `./gradlew :capabilities:camera:test :capabilities:camera:connectedDebugAndroidTest` on the connected phone; verify a short MP4 is playable and cleanup occurs on failure.
- [ ] **Step 7: Commit** `git add capabilities/camera && git commit -m "feat: record bounded camera video with MediaRecorder"`.

### Task 3: Add foreground-service ownership and recovery semantics

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/kotlin/com/fsaint/androidagent/AgentRuntimeService.kt`
- Modify: `app/src/main/kotlin/com/fsaint/androidagent/BackgroundJobManager.kt`
- Modify: `app/src/main/kotlin/com/fsaint/androidagent/BootReceiver.kt`
- Test: `app/src/test/kotlin/com/fsaint/androidagent/BackgroundJobManagerTest.kt`

**Interfaces:**
- `BackgroundJobManager` accepts `startVideo: suspend (VideoStartRequest) -> ToolResult<Unit>` and `stopVideo: suspend () -> ToolResult<VideoClip>` callbacks.
- `AgentRuntimeService` accepts an explicit `EXTRA_MEDIA_JOB_ID` and starts with foreground service type `camera|microphone` for media jobs.

- [ ] **Step 1: Write failing lifecycle tests** for video start entering `RUNNING`, stop producing `COMPLETED` with artifact metadata, camera/microphone conflict returning `DEVICE_BUSY`, and persisted active video becoming `INTERRUPTED` after recreation.
- [ ] **Step 2: Run** `./gradlew :app:test` and verify failures.
- [ ] **Step 3: Add manifest permissions and service type declarations** for camera, microphone, and foreground service use; do not add hidden/background camera access.
- [ ] **Step 4: Route video jobs through the foreground service** and update the notification with job ID, elapsed time, and stop action.
- [ ] **Step 5: Finalize video clips on stop/cancellation**, store them as `video/mp4`, and mark process-death media jobs `INTERRUPTED` without automatic restart.
- [ ] **Step 6: Run** `./gradlew :app:test :app:lintDebug` and verify lifecycle tests pass.
- [ ] **Step 7: Commit** `git add app && git commit -m "feat: run video jobs in foreground service"`.

### Task 4: Wire artifact delivery

**Files:**
- Modify: `app/src/main/kotlin/com/fsaint/androidagent/DarkLordApplication.kt`
- Modify: `app/src/main/kotlin/com/fsaint/androidagent/TelegramPhotoSender.kt` or create `TelegramMediaSender.kt`
- Modify: `app/src/main/kotlin/com/fsaint/androidagent/LocalChatApi.kt`
- Test: `app/src/test/kotlin/com/fsaint/androidagent/TelegramMediaSenderTest.kt`

**Interfaces:**
- Add `telegram.send_video` with required `artifactId` and optional current-chat `chatId`.
- Add `telegram.send_audio` with required `artifactId` and optional current-chat `chatId`.
- Local API job responses include `artifact.id`, `mimeType`, `sizeBytes`, and `durationMs`, never a path.

- [ ] **Step 1: Write failing sender tests** for successful MP4 upload, rejected non-video artifact, missing artifact, and network failure.
- [ ] **Step 2: Run** `./gradlew :app:test` and verify failures.
- [ ] **Step 3: Implement channel-native Telegram multipart uploads** using `sendVideo` and `sendAudio`, preserving the existing owner chat lookup and error mapping.
- [ ] **Step 4: Register handlers and update model tool schemas** in `OpenAiResponsesProvider.kt` so the model can deliver the artifact without asking for a chat ID.
- [ ] **Step 5: Run** app tests and verify local API responses remain text-safe.
- [ ] **Step 6: Commit** `git add app core/runtime && git commit -m "feat: deliver media artifacts through Telegram"`.

### Task 5: Add conversational and API test coverage

**Files:**
- Modify: `tools/chat_api_test.py`
- Modify: `docs/device-test/chat-api-50.md`
- Test: `core/runtime/src/test/kotlin/com/fsaint/androidagent/runtime/OpenAiResponsesProviderTest.kt`

- [ ] **Step 1: Add safe cases** for video tool inventory, unsupported-device messaging, and artifact metadata.
- [ ] **Step 2: Add opt-in side-effect cases** for `jobs.start(type=video)`, `jobs.status`, `jobs.stop`, cancellation, and Telegram video delivery.
- [ ] **Step 3: Add parser/schema assertions** for `camera.startVideo`, `camera.stopVideo`, `jobs.start`, and `telegram.send_video` arguments.
- [ ] **Step 4: Run** `python3 -m py_compile tools/chat_api_test.py` and the focused runtime tests.
- [ ] **Step 5: Document required camera/microphone permissions, foreground notification expectations, and artifact verification.**
- [ ] **Step 6: Commit** `git add tools docs core/runtime && git commit -m "test: cover video jobs through local chat API"`.

### Task 6: Connected-device acceptance and regression verification

**Files:**
- Modify: `docs/device-test/stage-12-background-runtime.md`
- Create: `docs/device-test/video-capture.md`

- [ ] **Step 1: Build and install** `./gradlew :app:installDebug --no-daemon --max-workers=2`.
- [ ] **Step 2: Confirm** `adb devices` and `curl http://dark-lord.curl-newton.ts.net:8765/health`.
- [ ] **Step 3: Run the safe suite** and confirm no regressions.
- [ ] **Step 4: Run focused owner-approved cases**: start a 5-second video, query status, stop it, inspect artifact metadata, and send it to the owner’s Telegram chat.
- [ ] **Step 5: Repeat while folded and locked**, confirming the visible foreground notification and successful stop.
- [ ] **Step 6: Force-stop/reboot during recording** and confirm the job becomes `INTERRUPTED`, no silent camera restart occurs, and any partial file is cleaned or marked explicitly.
- [ ] **Step 7: Capture `adb logcat` for failures**, run `./gradlew test :app:lintDebug`, and record the result in the device checklist.

## Self-review

This plan covers the missing adapter, service lifecycle, bounded artifacts, channel delivery, model schemas, tests, recovery behavior, and folded/locked device validation. It deliberately keeps video unsupported behavior truthful until Tasks 1–2 land and does not introduce a second job system.
