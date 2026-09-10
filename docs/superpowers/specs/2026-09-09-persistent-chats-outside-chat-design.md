# Persistent chats, Outside chat, and speech interruption

Status: behavior approved in conversation; written design awaiting review.

## Outcome

Dark Lord has named, persistent local chats. A pinned **Outside chat** connects side-button photos and spoken questions. The owner can photograph an object, interrupt the description, and ask about a detail in that same image. Both double press and long press stop existing conversational speech as soon as Dark Lord receives the invocation.

This is an architectural change, not just a prompt update. The current photo request uses the CAPTURE channel and a separate TTS instance; voice uses VOICE and `PushToTalkController`. `ConversationTranscript` currently records assistant/tool turns within a request, not complete multi-message chat history. Its checkpoint store is not a substitute for a chat repository.

## Approved behavior

- The main app offers a Chats screen: create, name, rename, and reopen chats; view messages and photo thumbnails; send typed messages. Settings remain accessible.
- Outside chat is pinned first. Side-button actions always target it, regardless of which chat is open in the main app or whether the phone is folded.
- Double press stops conversational speech, interrupts the previous local interaction, captures one new photo, persists it in Outside chat, then asks for a description and useful next actions.
- Long press stops conversational speech and starts listening. The recognized question joins Outside chat and uses its preceding messages and actual photo content.
- A new photo appends to the same chat. The most recent successfully stored photo is the default referent for “this” or “that.” Older retained photos can be selected explicitly in the chat UI.
- **New Outside chat** archives the existing thread and creates the new pinned thread atomically. Archived threads remain readable. Ordinary chats do not become the button target simply because the owner opens them.
- Chats survive process death and restart. After reboot, the existing first-unlock requirement remains.
- SMS, Telegram, and the existing local API retain their current routing in this release; their messages are not silently merged into Outside chat.

## Alternatives considered

1. **Persistent named chats with shared local interaction coordination (selected).** Addresses both context and interruption, with reusable history for the UI and both button paths.
2. **Pass only the latest photo into voice requests.** Smaller patch, but lacks named threads, reliable text history, and durable conversation boundaries.
3. **Unify every transport into one conversation.** Broader than requested and risks mixing audiences, authorization contexts, and reply destinations. Deferred.

## Components and ownership

### Chat repository

Add dedicated chat, message, and attachment-reference tables to the existing encrypted Room database through a non-destructive migration. Do not reinterpret existing checkpoint rows as complete chats or discard existing data.

A chat has an opaque ID, owner principal ID, title, creation/update timestamps, and an archived flag. A persisted owner-to-active-Outside-chat reference identifies the pinned thread independently of UI selection. Messages have opaque IDs, a per-chat monotonic sequence, role, text, input source, request ID, and completion state. Attachment references associate a message with a validated app-private artifact ID and MIME type. Roles include user, assistant, and tool; tool records retain execution identity and outcome separately from user-facing text.

Expose transactional operations to create/rename/list chats, append messages, complete or interrupt a request, and replace the active Outside thread. Enforce principal ownership on every lookup; a chat ID is not authorization. Serialize mutations per chat, allocate one request record per request ID, and use unique message IDs within it so duplicate callbacks cannot append the same result twice.

### Attachment lifetime

Keep image bytes out of message JSON and preferences. Reuse the artifact storage boundary, extending it with durable chat references so normal expiry does not remove images still referenced by retained chats. Retention metadata must survive process death. Referenced files still count toward storage limits: refuse a new capture attachment with a useful error when full instead of silently evicting a conversation's image.

Store a captured photo before starting model analysis. If analysis is interrupted, its saved photo remains available for the next voice question. If interruption wins before capture is committed, discard the late capture and explicitly mark that request interrupted; do not silently use an earlier photo as the failed new capture. Missing or explicitly deleted files display an unavailable attachment and are identified as unavailable to the model.

Archiving preserves attachments. Chat deletion and automatic history eviction are not part of this release. Handle unreferenced files left by interrupted writes through existing cleanup, without deleting referenced photos.

### Chat turn coordinator

Introduce a coordinator between UI/button entry points and `ConversationHarness`. It accepts principal, chat ID, source, request ID, user text, and optional attachment references. It persists user input, selects bounded context, invokes the existing authorized harness, and saves the result back to the same chat.

Allow one active model request per chat. Disable typed Send while that chat is busy, with Stop available; do not silently queue messages. A hardware invocation supersedes the current Outside request and the current local voice/photo interaction. Work in another named text chat is not cancelled merely because the owner uses a side-button action.

Keep chat history separate from the harness's per-request tool transcript and tool-call budget. Each new user message gets a fresh tool budget and event ID. Historical tool results are context only, never instructions to rerun an action. Existing effect tracking remains responsible for duplicate execution protection.

Do not change CAPTURE or VOICE authorization to a generic unrestricted channel merely to share context. Both channels can refer to the same owner chat while keeping the source's policy checks and response destination. SMS/Telegram identity and scoping remain unchanged.

### Model context

Extend the request/provider boundary to carry role-aware prior user and assistant messages plus validated images. Use structured JSON serialization for these messages; do not concatenate history as instructions or extend regex-based JSON assembly. Keep system instructions and untrusted conversation content separate.

Initial context limits: most recent 20 completed user/assistant messages, at most 24,000 characters of prior message text, plus the current input and up to two retained images. Include the latest photo by default; an explicitly selected earlier photo takes priority, with the latest photo included if it fits. Bound each image using existing capture limits and cap the combined raw image payload at 8 MB. Indicate omitted history/attachments in model context; do not imply all older images are visible. Persist full history even when the request includes only a bounded window.

Preserve available tools, MCP, and skills through the existing context builder. Image access is checked against chat ownership before resolving bytes. A follow-up must receive actual retained image content, not merely the assistant's earlier description. Unit tests inspect the outgoing structured request to prove this independently of the model's answer.

### Shared speech and interruption

Use one application-owned conversational output controller for photo and voice responses. Services hold per-turn leases; destroying an old service releases only its lease and must not shut down the shared speaker or stop a newer turn. This controller covers conversational TTS, not unrelated music, calls, or independent recording jobs.

Each accepted photo/voice invocation obtains a monotonically increasing generation token. Handle interruption in this order:

1. Invalidate the old generation and stop active/queued conversational TTS immediately, before permissions, capture, database work, or network requests.
2. Cancel the superseded recognition/model job where possible and record interruption. In-flight hardware cleanup can finish without blocking the speech stop.
3. Start the new capture or listening interaction under its generation.
4. Require a matching generation for every recognition callback, model result, notification update, speech request, and speech-completion callback.

A stale callback cannot speak, overwrite the current state, or stop the new turn. Starting another photo must supersede the old photo turn rather than being ignored by the current single-flight service guard. If the owner presses while listening, cancel that recognition session and start a fresh one; late recognition results are ignored.

Cancellation cannot undo a sent text or another completed tool effect. Preserve completed tool outcomes in the audit/history, suppress stale automatic speech, and stop further dispatch when cancellation is observed. If an operation's completion is unknown, record that uncertainty rather than reporting rollback or retrying automatically. Interrupting speech alone does not delete an already completed answer.

### Android entry points and UI

Keep Samsung's existing routine/shortcut and Assistant registrations. Route their invocation handlers through the shared interruption boundary before starting camera/microphone work. Foreground services continue to own the work when Samsung hides an activity; UI collectors only render state. Long-press speech recognition retains the existing end-of-speech behavior because physical key release is not delivered reliably.

Provide a normal Chats destination in MainActivity and retain the explicit photo shortcut. Do not silently change the tested Samsung double-press mapping while separating normal app navigation from capture entry points. Photo notifications open the corresponding chat/request, not a global “latest result,” and never capture again. Use private lock-screen notification content. Opening a chat does not automatically read it aloud; photo/voice responses do. Typed responses remain silent.

Show listening, capturing, thinking, speaking, interrupted, and failed states. Provide a Stop control during active interaction. A text composer and history remain usable when camera, microphone, or speech is unavailable. No persistent cover-display UI, always-listening microphone, or automatic call interruption is promised.

## Failure and recovery

- A denied camera/microphone permission stops the attempted input with a useful message; previously playing speech remains stopped.
- A model timeout or failure keeps the user message and any committed image, marks the attempt failed, and allows a new question or explicit retry. Retrying analysis uses the saved photo rather than recapturing it.
- TTS failure preserves the written answer. Never expose raw provider exceptions, credentials, or image bytes in notifications/logs.
- On process restart, pending requests become interrupted; do not automatically repeat capture, tool effects, or speech. Completed messages and the Outside pointer reload after unlock.
- Migrating from the current database preserves owner, keys, grants, Telegram configuration, and existing records. The old photo-result preference may remain as legacy data, but it must not be presented as a recovered photo chat when the original image/history is unavailable.

## Verification and acceptance

Use deterministic unit tests for repository ordering, owner isolation, Outside thread replacement, bounded context, retained image selection, fresh per-message tool budgets, and interleaved cancellation callbacks. Test interruption during capture, analysis, speech, and recognition; delayed old callbacks must never affect a newer generation. Include service lease tests and request deduplication.

Test the Room migration and artifact retention across process restart on a disposable emulator with populated pre-migration fixtures. Run build, unit tests, and lint. Do not use Gradle `connectedDebugAndroidTest`, uninstall, or clear-data workflows on the owner's configured phone. The September 9 runner cleanup erased its setup; migration/device tests must not repeat that incident. Upgrade the physical phone only through a non-destructive install, then verify settings remain present before any hardware test.

High-impact physical acceptance sequence:

1. Create two named chats; exchange messages and verify their contexts stay separate.
2. Fold the phone and double-press while pointed at a recognizable object; verify the photo and answer appear in Outside chat.
3. Long-press during the spoken answer: speech stops before listening. Ask about a visible detail and verify the answer uses the saved photo without another capture.
4. Double-press during a voice answer: the old answer stops, one new photo is added, and only the new answer speaks.
5. Interrupt while analysis is pending and repeat presses quickly; no delayed old speech or duplicate message appears.
6. Reopen the app, then reboot/unlock once; history and photos persist without automatic speech. Start a fresh Outside chat and verify the old one remains archived and separate.

Record generation/request IDs, phase transitions, and bounded timing measurements without recording credentials or raw private content. App-received invocation-to-stop dispatch has a target of under 100 ms in deterministic/instrumented testing; separately report physically observed audible cutoff, since Samsung dispatch and TTS buffering are outside the app's complete control.

## Next step

Review this written design, then prepare the implementation plan. No application implementation or device installation is included in this design-only change.
