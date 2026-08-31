# Telegram Access Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add owner-configured Telegram Bot API access so Dark Lord can receive Telegram messages and send agent replies through the existing runtime.

**Architecture:** Store the bot token in Android Keystore-backed encrypted preferences using the existing OpenAI secret pattern. Add a platform-independent Telegram transport/client with bounded HTTPS calls, then wire Telegram updates into `CommunicationsDispatcher` and replies out through the bot API. Expose owner-only token setup and connection status in the existing main settings UI.

**Tech Stack:** Kotlin, coroutines, Android Keystore, `HttpURLConnection`, Jetpack Compose, JUnit/Kotlin tests.

**Spec:** `docs/superpowers/specs/2026-08-30-agent-harness-design.md`

## Global Constraints

- Telegram credentials are owner-only, encrypted at rest, and never logged or included in diagnostics.
- HTTPS only; bounded request/response sizes and timeouts.
- Telegram is an additional channel and must reuse the existing agent runtime and authorization policy.
- No background polling loop without lifecycle/cancellation handling.
- Preserve existing SMS, voice, and notification behavior.

### Task 1: Telegram credential store and owner settings

**Files:**
- Create: `core/runtime/src/main/kotlin/com/fsaint/androidagent/runtime/TelegramCredentials.kt`
- Create: `app/src/main/kotlin/com/fsaint/androidagent/TelegramBotSecretStore.kt`
- Modify: `app/src/main/kotlin/com/fsaint/androidagent/DarkLordApplication.kt`
- Modify: `app/src/main/kotlin/com/fsaint/androidagent/MainActivity.kt`
- Modify: `app/src/main/kotlin/com/fsaint/androidagent/ui/OpenAssistantScreen.kt`
- Test: `core/runtime/src/test/kotlin/com/fsaint/androidagent/runtime/TelegramCredentialsTest.kt`

**Interfaces:**
- Produce `TelegramBotTokenProvider.apiToken(): String` and owner-only `TelegramBotCredentialStore.set(owner, token): CredentialOutcome`.
- Reuse `PrincipalRole.OWNER` and the existing encrypted preference pattern.

- [ ] Test token validation, owner-only writes, trimming, and blank/oversized rejection.
- [ ] Implement the provider/store contracts and Android Keystore-backed persistence.
- [ ] Add an owner-only Telegram token field and save-result feedback to the main screen.
- [ ] Run focused tests and commit `feat: add owner Telegram bot credentials`.

### Task 2: Telegram Bot API transport and messaging client

**Files:**
- Create: `core/runtime/src/main/kotlin/com/fsaint/androidagent/runtime/TelegramBotClient.kt`
- Create: `app/src/main/kotlin/com/fsaint/androidagent/TelegramHttpTransport.kt`
- Test: `core/runtime/src/test/kotlin/com/fsaint/androidagent/runtime/TelegramBotClientTest.kt`

**Interfaces:**
- Produce `TelegramBotClient.sendMessage(chatId: String, text: String): TelegramResult` and `getUpdates(offset: Long?, timeoutSeconds: Int): List<TelegramUpdate>`.
- Consume `TelegramBotTokenProvider`; use HTTPS endpoints under `https://api.telegram.org/bot{token}/...`.

- [ ] Test URL construction without exposing the token, successful JSON parsing, Telegram API errors, payload limits, and timeout normalization.
- [ ] Implement bounded `sendMessage` and long-poll `getUpdates` with injectable transport.
- [ ] Implement Android transport with redacted status logging only.
- [ ] Run focused tests and commit `feat: add Telegram Bot API client`.

### Task 3: Telegram inbound/outbound channel integration

**Files:**
- Create: `app/src/main/kotlin/com/fsaint/androidagent/telegram/TelegramUpdateService.kt`
- Modify: `app/src/main/kotlin/com/fsaint/androidagent/DarkLordApplication.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `core/runtime/src/main/kotlin/com/fsaint/androidagent/runtime/RuntimePorts.kt`
- Test: `app/src/test/kotlin/com/fsaint/androidagent/telegram/TelegramUpdateServiceTest.kt`

**Interfaces:**
- `TelegramUpdateService` owns cancellable polling and maps text updates to `AgentEvent(type="telegram.received", channel="TELEGRAM")`.
- Telegram reply sender implements existing `ReplySender` and sends to the originating chat ID.

- [ ] Test update-to-event mapping, offset advancement, duplicate update suppression, cancellation, and reply routing.
- [ ] Wire the service into application lifecycle without blocking startup.
- [ ] Add only required manifest/network declarations; do not request SMS permissions for Telegram.
- [ ] Run app/unit tests and commit `feat: route Telegram through agent runtime`.

### Task 4: Documentation and device smoke test

**Files:**
- Modify: `README.md`
- Modify: `docs/GETTING_STARTED.md`
- Test: `app/src/androidTest/kotlin/com/fsaint/androidagent/TelegramSettingsUiTest.kt`

- [ ] Document BotFather setup, token storage, chat authorization, polling lifecycle, and revocation.
- [ ] Add a short acceptance checklist for owner setup, inbound message, model reply, and denied unknown chat.
- [ ] Run full relevant Gradle tests and build/install the APK for device verification.
- [ ] Commit `docs: document Telegram bot access`.
