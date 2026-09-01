# Artifact Sharing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give Dark Lord a scoped artifact store so captured media can be safely passed to Telegram and future MCP/channel adapters.

**Architecture:** Store bounded artifacts in app-private storage with metadata and TTL. Expose artifact IDs through the tool router, never raw filesystem paths. Add Telegram photo upload consuming an artifact ID and enforce owner scope at the router.

**Tech Stack:** Kotlin, Android app-private files, Room, multipart HTTPS, existing scoped tool router.

**Spec:** The artifact-sharing design in the user request and the architecture described in this plan.

## Global Constraints

- No arbitrary filesystem paths are exposed to the model.
- Artifacts are owner-scoped, size-bounded, MIME-validated, and expire.
- Telegram uploads use HTTPS and bounded streaming; tokens never appear in logs or errors.
- Existing camera and Telegram text behavior must remain compatible.

---

### Task 1: Artifact store and tools

**Files:** Create `app/src/main/kotlin/com/fsaint/androidagent/artifacts/ArtifactStore.kt`; test `app/src/test/kotlin/com/fsaint/androidagent/artifacts/ArtifactStoreTest.kt`.

- [ ] Write tests for storing bytes, metadata, owner scope, size rejection, and expiry.
- [ ] Run `./gradlew :app:testDebugUnitTest --tests '*ArtifactStoreTest' --no-daemon` and observe failure.
- [ ] Implement bounded app-private storage with opaque IDs, MIME allowlist, metadata, and TTL cleanup.
- [ ] Add `artifact.store`, `artifact.metadata`, and `artifact.open` handlers returning IDs/metadata, never paths.
- [ ] Run the focused test and commit `feat: add scoped artifact store`.

### Task 2: Telegram media transport

**Files:** Modify `core/runtime/src/main/kotlin/com/fsaint/androidagent/runtime/TelegramBotClient.kt`, `app/src/main/kotlin/com/fsaint/androidagent/TelegramHttpTransport.kt`; tests in existing Telegram test files.

- [ ] Test multipart `sendPhoto` request creation and token redaction.
- [ ] Implement bounded multipart upload from an artifact byte stream, accepting only HTTPS Telegram endpoints.
- [ ] Add `telegram.send_photo` tool wiring with owner scoping and clear failure results.
- [ ] Run runtime/app tests and commit `feat: send artifacts through telegram`.

### Task 3: Camera integration, cleanup, and device verification

**Files:** Modify camera capability integration and `DarkLordApplication.kt`; add device test and update `README.md`/`docs/getting-started.md`.

- [ ] Test that camera capture stores an artifact reference and does not expose a filesystem path.
- [ ] Register artifact and Telegram media handlers in the live scoped router.
- [ ] Add periodic cleanup on runtime start and document `camera.capture` followed by `telegram.send_photo`.
- [ ] Run unit tests, connected tests, install, and verify one owner Telegram photo delivery on the device.
- [ ] Commit `feat: connect camera artifacts to telegram`.
