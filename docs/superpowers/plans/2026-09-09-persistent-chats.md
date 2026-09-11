# Persistent Chats Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Named durable chats, a photo/voice Outside conversation, and reliable interruption.

**Architecture:** Room owns chat history and the Outside pointer. A chat coordinator builds bounded model history independently of the tool-loop transcript. An application-owned generation gate and speaker arbitrate photo/voice turns; UI and foreground services render or lease that work.

**Tech Stack:** Kotlin, coroutines, Room 2.7, Compose Material 3, Android TTS, kotlinx JSON.

**Spec:** `docs/superpowers/specs/2026-09-09-persistent-chats-outside-chat-design.md`

## Global Constraints

- Work on master as requested, preserving existing uncommitted edits. Do not push.
- Preserve owner, credentials, permissions, transport routing and authorization.
- Do not uninstall, clear data, or run connectedDebugAndroidTest on the physical phone.
- Context: 20 messages, 24,000 prior text characters, two images, 8 MB raw images.
- Fresh per-user-message tool budget. No replay of previous tool effects.
- Hardware actions always select Outside; text chats stay separate and silent.
- No automatic capture, speech, or tool retries after restart.

## Task 1: Durable chat repository and context selection

**Files:** create `core/runtime/src/main/kotlin/com/fsaint/androidagent/runtime/ChatHistory.kt`; create `core/data/src/main/kotlin/com/fsaint/androidagent/data/ChatRepository.kt`; modify `core/data/src/main/kotlin/com/fsaint/androidagent/data/AgentDatabase.kt`; tests in the matching `src/test` package.

**Interfaces:** `ChatRepository(database)` exposes suspend `outside(ownerId)`, `newOutside(ownerId)`, `create(ownerId,title)`, `rename(ownerId,chatId,title)`, `messages(ownerId,chatId)`, `begin(ownerId,chatId,requestId,text,source,artifactId?)`, `finish(ownerId,requestId,text,state)`, `recover()`, and owner-filtered observing flows. `ChatContext.select(messages,selectedArtifactId)` produces bounded prior role/text and attachment IDs.

- [x] Write failing repository tests for owner isolation, idempotent begin/finish, monotonic order, archived Outside, interrupted recovery, migration preservation; write pure context tests for last-photo selection and bounds.
  ```kotlin
  val old = repository.outside("owner")
  val fresh = repository.newOutside("owner")
  assertNotEquals(old.id, fresh.id)
  assertTrue(repository.list("owner").first { it.id == old.id }.archived)
  ```
- [x] Run `./gradlew :core:runtime:test :core:data:testDebugUnitTest` and verify failures expose missing behavior.
- [x] Add chat/request/message/attachment/Outside tables via migration 6→7 and use Room transactions. Preserve existing tables. Implement context selection without changing tool transcript counters.
  ```kotlin
  database.withTransaction {
      require(dao.chat(chatId)?.ownerId == ownerId)
      if (dao.request(requestId) == null) dao.insertRequest(request)
  }
  ```
- [x] Re-run the focused tests and review migration SQL against generated schema.

## Task 2: Durable referenced artifacts

**Files:** modify `app/src/main/kotlin/com/fsaint/androidagent/artifacts/ArtifactStore.kt` and its `ArtifactStoreTest.kt`.

**Interfaces:** add `retain(id,reference)`, `release(id,reference)`, durable metadata reload and bounded total storage. Existing artifact tool handlers keep their interface.

- [x] Test store/recreate/read, retained expiry, unreferenced expiry, corrupt metadata, and quota refusal without eviction.
  ```kotlin
  val photo = first.store(byteArrayOf(1,2), "image/jpeg")
  first.retain(photo.id, "chat-message")
  now += 100_000
  assertNotNull(ArtifactStore(directory, ttlMillis = 10, clock = { now }).read(photo.id))
  ```
- [x] Run `./gradlew :app:testDebugUnitTest --tests '*ArtifactStoreTest'` red, implement atomic metadata persistence and retain/release, then run green.

## Task 3: Model history and chat-turn execution

**Files:** modify runtime `ConversationHarness.kt`, `OpenAiResponsesProvider.kt`, `core/runtime/build.gradle.kts`; create runtime `ChatTurnCoordinator.kt` and tests.

**Interfaces:** `ConversationRequest` gains prior role-aware messages and image attachments with defaults preserving old callers. `ChatTurnCoordinator.run(owner,chatId,source,text,requestId,artifactId?,selectedArtifactId?)` persists input, reads owned context, invokes an injected model runner, then persists completed/interrupted/failed results.

- [x] Test photo → follow-up includes image bytes, no cross-chat context, cancellation retains input, and each request starts a new tool budget.
- [x] Test outgoing JSON parses and separates instructions/user/assistant roles, escaping quotes/newlines and preserving tool inventory.
  ```kotlin
  val input = Json.parseToJsonElement(captured.body).jsonObject["input"]!!.jsonArray
  assertTrue(input.any { it.jsonObject["role"]?.jsonPrimitive?.content == "assistant" })
  ```
- [x] Run focused tests red. Add structured JSON request serialization; retain legacy response compatibility while parsing real Responses output structurally.
- [x] Implement chat coordination and rerun focused tests green. Audit cancellation propagation and tool effects.

## Task 4: Interruptible local interactions

**Files:** create app `voice/InteractionGate.kt`; modify `PushToTalkController.kt`, `AndroidSpeechRecognizerPort.kt`, `PhotoConversationActivity.kt`, `PhotoConversationService.kt`, `VoiceCaptureService.kt`, and `DarkLordApplication.kt`; add focused unit tests.

**Interfaces:** `InteractionGate.begin():Long`, `isCurrent(token):Boolean`, `attach(token,job)`, `stop(token?)`, `speaker(token):Speaker`. One app-owned TTS engine backs per-generation speakers; cancellation invalidates old callbacks before invoking stop.

- [x] Test stale speech/completion rejection, recognition replacement, press while thinking, and old service cleanup not stopping a newer lease.
  ```kotlin
  val old = gate.begin()
  val fresh = gate.begin()
  gate.speaker(old).speak("stale") {}
  assertTrue(gate.isCurrent(fresh))
  assertTrue(recordedSpeech.isEmpty())
  ```
- [x] Run focused tests red. Route both hardware entry points through the gate before permission/capture work. Keep foreground service lifetime bounded and generation-specific. Use new recognizer listener/session identities.
- [x] Connect photo/voice to the same Outside repository pointer and coordinator, keeping CAPTURE/VOICE scope unchanged. Notifications link immutable chat/request identity. Run focused tests green.

## Task 5: Chats screen and verification

**Files:** create app `chat/ChatsScreen.kt`; modify `MainActivity.kt`, strings as needed, README/getting-started; add coordinator/UI route tests and a device acceptance report.

- [x] Add tests for silent typed replies, busy Send refusal, Stop, and explicit earlier-photo selection. Run red before route/controller implementation.
- [x] Build a Material 3 list/detail flow with pinned Outside, New/Rename, archive-preserving New Outside, message history, selectable thumbnails, composer, phase/error text, Stop, and Settings. Preserve Samsung shortcut mapping; normal launcher opens Chats rather than capture.
  ```kotlin
  BackHandler(selectedChatId != null) { selectedChatId = null }
  OutlinedTextField(value = draft, onValueChange = { draft = it }, label = { Text("Message") })
  Button(enabled = draft.isNotBlank() && !busy, onClick = send) { Text("Send") }
  ```
- [x] Run full `./gradlew test :app:lintDebug :app:assembleDebug`, inspect results, and review diff for races, missing permissions and migration loss.
- [x] Document actual tests and remaining physical acceptance, without claiming untested folded behavior. No device install is required by this implementation request; offer safe upgrade after verification.

## Execution record

Implemented on master without committing, pushing, or installing on the configured physical phone. Existing changes are preserved.

- The coordinator lives in core/runtime rather than app so persistence/model tests can exercise it without Android services.
- Added Room 6→7 migration tests using a populated version-6 fixture under Robolectric; owner and legacy message records remain intact. Repository and artifact reopen/retention tests pass. This is host-side migration verification, not a physical-device upgrade test.
- Red/green checks reproduced missing repository/artifact behavior, uncancellable HTTP transport, rapid-press admission failure, and stale attachment commits. Follow-up review added generation checks within database transactions and explicit supersession that preserves late tool outcomes without stale answers.
- Historical tool/status entries are bounded informational context, so an interrupted request does not lose recorded effects. Retention reconciliation repairs incomplete chat/artifact pin writes after restart.
- Material 3 list/detail UI includes saved drafts, archived threads, owned photo previews/selection, busy/Stop controls, and Settings navigation. The normal launcher no longer captures automatically; the explicit Samsung Photo chat shortcut remains.
- Full unit tests, lint, and debug build passed. Emulator-only instrumentation covers chat creation, Outside archiving, Settings navigation, saved messages, and unsent drafts after activity recreation; the fixture test refuses physical hardware. Espresso was updated only in androidTest dependencies for API 36 emulator compatibility.
- Physical capture, voice recognition, audible cutoff timing, secure lock/folding, and live model follow-up remain the manual acceptance sequence in the spec. No phone API suite was run and no physical settings were changed.
- See docs/device-test/2026-09-09-persistent-chats.json for final verification evidence and pending acceptance. The debug APK is ready for a separately requested non-destructive upgrade.
