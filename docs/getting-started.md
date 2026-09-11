# Getting started

Dark Lord is an Android prototype intended for a dedicated, no-root device. The documented development target is a Samsung Galaxy Z Flip3 running Android 15.

## Requirements

- Android Studio with the repository's configured JDK/Android SDK.
- An Android 15 device with USB debugging enabled.
- An OpenAI API key for conversational responses.
- ADB available on the development machine.

## Build and install

From the repository root:

```sh
./gradlew test lintDebug :app:assembleDebug
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For the signed prototype artifact, use `./gradlew :app:assembleRelease` and follow the [sideloading guide](release/sideloading.md).

## First-time device setup

1. Launch **Dark Lord** and open **Settings** (or **Set up owner** on a fresh install).
2. Grant microphone, camera, SMS, phone, and notification permissions when prompted.
3. Tap **Make Dark Lord your Assistant** and accept the role prompt. Then open Settings › Advanced features › Side button › **Press and hold** and choose the digital assistant so a long press of the side key opens Dark Lord. Confirm with `adb shell settings get secure assistant`.
4. Open **Communications settings**, provision your owner number, and grant SMS/dialer roles as needed.
5. Enable notification access for Dark Lord.
6. Return to **Settings** and enter the OpenAI key in **OpenAI API key (owner only)**, then tap **Save model key**. The key is encrypted with Android Keystore and is not sent through SMS or diagnostics.
7. For reliable Telegram polling while the Flip is folded or locked, open the Android battery settings for Dark Lord, choose unrestricted battery/background usage, and keep the persistent runtime notification enabled. If notification permission or the **Agent runtime** channel is disabled, Dark Lord does not restore the background runtime; use **Open notification settings** in the app to re-enable it.

Detailed reset and Device Owner procedures are in the [device provisioning guide](device-provisioning/galaxy-z-flip3-reset-and-device-owner.md).

## First test

Send an SMS to the device, or invoke Dark Lord with the Side button, and ask a simple question such as `What is my battery level?` The model receives only the tools allowed by the active principal's scope. It may call a device tool and then sends one final response.

To test voice, hold the side key, speak the same request, and release. Dark Lord listens as soon as the assistant surface appears, finalizes when you stop talking (or when you tap the surface), and speaks the reply while showing it on the cover display when folded or on the main screen when open. Android does not report the side-key release to apps, so the end of your sentence is what sends the request. If no key is configured, Dark Lord returns a setup message instead of crashing. Use the [Stage 13 push-to-talk checklist](device-test/stage-13-side-key-push-to-talk.md) for the full sequence.

### Side-button photo conversation

Keep the explicit **Dark Lord : Photo chat** action in the Samsung double-press routine described below. The regular **Dark Lord** app entry now opens **Chats**, not the camera. Photo chat takes one still image, saves it in **Outside chat**, sends it to the multimodal model, and shows a description with suggested next actions. Camera permission and an owner OpenAI key are required. Open **Settings** from Chats to configure the app.

Photo processing runs in a short-lived foreground service, with **taking a picture**, **analyzing picture**, and **photo answer ready** notifications. Enable notification permission to see them. The service speaks the answer using Android text-to-speech and saves the written answer in the chat. Tapping a result notification opens its chat without taking another photo or speaking again. Processing has a two-minute timeout; the service stops after speech finishes or times out. Notification text is private on the lock screen, but spoken answers can be heard by people nearby.

Voice and photo-triggered spoken replies are instructed to use 1–3 short sentences by default. Longer answers should summarize the important points and offer the full version. Ask for the full version, or accept the offer, to request more detail. This is model guidance, not a hard output-length limit or a separately stored full-length answer. SMS, Telegram, and typed chat do not receive this brevity instruction.

Developer testing warning: do not run Gradle `connectedDebugAndroidTest` on a configured personal device without a verified backup and approval to reset it. In the September 9 device test, its cleanup removed the target app and local setup. Use a disposable device for this task; installing with `installDebug` alone does not perform that test cleanup.

For the experimental folded-phone route on the tested Flip3, assign double press to a manual Samsung routine containing **Open an app or do an app action → Dark Lord : Photo chat**. Physical folded capture was observed through this route; end-to-end spoken delivery with the new service still requires device verification. This does not provide a persistent cover-screen UI. After restarting the phone, unlock it once before using Dark Lord: runtime initialization waits for credential-protected storage to become available.

Dark Lord also registers for Android's `android.media.action.STILL_IMAGE_CAMERA`
and `MAIN` / `APP_CAMERA` launch intents. To test the camera entry point:

```bash
adb shell am start -W -a android.media.action.STILL_IMAGE_CAMERA -p com.fsaint.androidagent
```

This registration does not replace Samsung's folded-camera shortcut. On the tested
Galaxy Z Flip3 (Android 15), Side button → Camera → Open Camera explicitly targets
`com.sec.android.app.camera/.Camera`, bypassing Android's camera-app resolver.
Closed-phone double-press capture remains unsupported through that route. Dark Lord
does not advertise `IMAGE_CAPTURE` (returning a photo to another app) or
`STILL_IMAGE_CAMERA_SECURE` (a separate restricted lock-screen camera contract).

## Chats and Outside chat

The normal app launcher opens **Chats**. Use **New chat** to create a named conversation, **Rename** to change its name, and **Settings** to configure Dark Lord. Typed replies are silent. Send is disabled while that chat is busy; **Stop** interrupts its request. Other named chats do not share their history.

**Outside chat** is pinned first and is always the side-button destination, even if another chat is open:

1. Double-press the configured Photo chat action to add a picture and hear suggested next actions.
2. Long-press the Assistant action to stop the current spoken answer and ask about that picture. No new capture is needed. Both button actions stop conversational speech as soon as the app receives the invocation.
3. Open Outside chat to read history, type a follow-up, or select **Ask about this photo** under an older image. Otherwise the latest successfully saved photo is used.
4. When idle, choose **New Outside chat** to archive the thread and start fresh. The old thread and its photos remain readable.

Saved photos remain available if model analysis fails or is interrupted. If capture was interrupted before saving, the history explicitly says no new photo was saved. Restarted requests are marked interrupted, never automatically replayed. Unlock once after reboot to make protected storage available.

Full history stays on the device; each model request uses a bounded window of up to 20 history entries, 24,000 prior text characters, and two photos totaling at most 8 MB. Historical tool outcomes are data, not instructions to repeat effects. Referenced photos survive normal artifact expiry; they count toward the 128 MiB artifact-store limit. A full store refuses new artifacts instead of silently evicting chat photos. Missing files are reported as unavailable. Chat deletion is not included yet.

SMS, Telegram, and the existing local chat API keep their separate routing. This feature does not add a persistent cover-screen UI or bypass Samsung restrictions. Folded capture, audible interruption timing, and live photo-to-voice follow-up must still be checked on the physical phone after a non-destructive upgrade.

### Speech recognition recovery

Dark Lord reuses the Android speech recognizer between successful utterances. After a voice session closes, an inactive connection may remain for up to 30 seconds to support another press; this does not keep recording audio. Interrupting a capture cancels it and invalidates its callbacks.

If the speech service disconnects before it becomes ready, Dark Lord shows **Reconnecting speech…** and retries once after 300 ms. Once readiness, speech, or partial results have been observed, it does not retry automatically because part of your command might be lost. Press again and repeat the complete request. Release or tap while reconnecting cancels the pending retry. Readiness and final results each have a five-second deadline; network, permission, busy-service, and disconnection errors are distinguished.

Validate this through the physical side button, not the text chat API. The [speech recovery checklist](device-test/2026-09-10-speech-recovery.md) records automated evidence and the remaining physical checks. Diagnostic logs use `DarkLordVoice` and contain callback types, attempt/turn IDs, elapsed time, and error codes, never transcript text or audio.

## Run Python from the agent

Owner sessions can ask Dark Lord to run Python code, save scripts, and run them later. The embedded Python environment includes `requests` and `Pillow` and enforces bounded execution and output limits.

```python
import dark_lord
battery = dark_lord.call_tool("device.battery")
photo = dark_lord.call_tool("camera.capture")
artifact_id = photo["payload"]["id"]
```

Use `dark_lord.artifact_create(data, mime_type)` for generated files and return the artifact ID to the agent for delivery through Telegram or another adapter. Android permissions still apply, and scripts cannot obtain root or bypass the app sandbox. Use `python.exec`, `python.save`, `python.list`, `python.run`, and `python.delete` through the conversational agent.

## Add an MCP server

1. Open **MCP server settings** from the main screen.
2. Enter a display name and an HTTPS Streamable HTTP endpoint, such as `https://mcp.example.com/mcp`.
3. Use a server that does **not require authentication**. OAuth fields are retained as metadata only; entering them does not authenticate a connection. Do not paste secrets into the name or OAuth metadata fields.
4. Tap **Save MCP server**. The configuration is stored in the encrypted Room database and discovery starts. Wait for **Ready** and a nonzero tool count. For an already-saved server, tap **Refresh**.
5. As the configured owner, ask “What tools are available from [server name]?” Then request one known read-only tool from that server. Saved connections are discovered automatically for new conversations; successful catalogs are cached for five minutes. Refresh forces rediscovery. An in-progress turn retains its tool snapshot.
6. Owner sessions can access configured servers. Other principals need an explicit MCP resource grant; unknown principals get no access by default. Authorization is checked both when advertising tools and before dispatch.

Settings shows the last check, discovered names/count, and a sanitized failure reason. **Authentication required** means this release cannot use that server. **No usable tools** means the server offers none or its descriptors use unsupported schemas/execution modes. **Unreachable** may mean an incorrect endpoint, TLS/network failure, or timeout. Use the actual Streamable HTTP endpoint, not a server's homepage or legacy `/sse` endpoint. HTTPS is required, redirects are not followed, and explicitly configured private/Tailscale hosts are allowed with normal certificate verification.

SMS, Telegram, local API, typed chats, photo chats, and voice all use the shared MCP-enabled harness. Remote tools have stable namespaced aliases to avoid name collisions. Nested objects, arrays, numbers, booleans, and null retain their JSON types. Discovery is bounded to 64 tools per server; the model's combined phone/remote catalog is capped at 128 tools, so some remote tools may be omitted. Unsupported schema forms (including references and regex constraints) are omitted with a notice instead of rewritten. Binary MCP results are reported but not delivered as artifacts yet. If a dispatched call times out or loses its session, it is not automatically repeated because it may already have completed.

Automated protocol/harness coverage is recorded in [the MCP integration report](device-test/2026-09-09-live-mcp-integration.json). [Live phone API acceptance](device-test/2026-09-10-mcp-api-acceptance.json) verified the saved no-auth server's 34-tool inventory and one successful `whoami` execution. The [Stage 8 checklist](device-test/stage-8-mcp-skills.md) tracks broader MCP/Tailscale work; this test does not establish OAuth or inbound MCP support.

Dark Lord's inbound MCP server is currently a scoped protocol foundation (`TailscaleMcpServer`) rather than an always-on public endpoint. A live Tailscale listener and endpoint advertisement are still required before another MCP client can connect to the phone.

Use the [Stage 11 conversational harness checklist](device-test/stage-11-conversational-harness.md) for the complete smoke test. The [acceptance checklist](acceptance/flip3-prototype-checklist.md) tracks the broader device evidence still required.

### Verify MCP through the phone API

The agent can call `mcp_inventory` to obtain authorized server names, statuses, tool counts, and tool aliases. Ask “List the configured MCP servers and explain which are enabled.” This reads app-backed inventory rather than relying on the model to infer connection status.

Enable **Local chat API** in Settings on your dedicated test phone. It is an unauthenticated, owner-level development interface: keep it private, never expose it to the public internet, and disable it when finished. Over USB:

```sh
adb forward tcp:18765 tcp:8765
curl http://127.0.0.1:18765/health
python3 tools/mcp_api_smoke.py \
  --url http://127.0.0.1:18765 \
  --server test \
  --read-only-tool whoami \
  --report /tmp/dark-lord-mcp-api-smoke.json
adb forward --remove tcp:18765
```

Replace `test` with the exact saved display name and `whoami` with a known, operator-approved read-only tool supported by that server. The script requires real inventory execution, a Ready server, and one verified successful call to that server's discovered tool alias. It does not automatically retry a remote action. With Tailscale active, the private phone URL can replace the USB URL.

`POST /chat` still accepts plain text and returns `reply`. It now also returns `stopReason` and `toolCalls` entries containing `tool`, `success`, `error`, and `verification`. A successful inventory call additionally returns structured `mcpInventory`. Tool arguments and raw result bodies are excluded from this execution trace; ordinary model replies may still contain requested data. A reassuring reply alone is not proof a tool ran. This endpoint remains stateless and does not exercise persistent Outside chat history.

You can use ordinary wording, such as “can you read my email,” without naming an MCP tool. The model receives current authorized tool purposes after saved conversation history, with guidance to discover connected accounts before claiming access. Tool presence is not proof of account authorization. Capability questions do not authorize sending, deleting, or changing data.

The harness gives common unverified access denials one corrective pass when remote task tools are present. During that pass, historical assistant access-denial prose is omitted from model context, while user requests, recorded tool outcomes, and the stored chat remain intact. It may automatically consult the app's read-only `mcp_inventory`; it does not automatically repeat remote task actions. If the model still denies access without a task-tool result, the response states that access is unverified and the API reports `CAPABILITY_UNVERIFIED`. This is a bounded English-language denial guard, not a general verifier of every model statement.

To test natural wording against an expected read-only workflow, add `--natural-prompt 'can you read my email' --read-only-tool gmail_list_accounts --allow-read-only-tool gmail_list_messages` to the smoke command above (replace the existing `--read-only-tool` argument). Success requires the discovered account-listing alias to execute with a verified successful result, not merely an affirmative reply. Additional tools must be explicitly allowed and return verified success. This is a post-execution assertion, not a permission boundary; only supply authorized read-only test prompts. Persistent chat/voice tool outcomes are also logged under `DarkLordTools`, without arguments or result contents.

Use the [Stage 12 background runtime checklist](device-test/stage-12-background-runtime.md) when validating folded and locked operation. Its automated instrumentation covers the foreground service, notification Stop/Restart actions, Telegram checkpointing, duplicate-start protection, notification gating, and a non-secure keyguard SMS/notification handler check. On the secure API 35 Flip, the class currently completes with four passes and one intentional keyguard skip so it cannot strand later tests behind the PIN screen. Physical hinge folding, secure-lock delivery, live owner Telegram/SMS delivery, real Android Notification Access delivery from another app, and force-stop/relaunch recovery remain manual device steps. The persistent foreground service keeps Telegram polling and queued work visible, but Android and Samsung policy can still delay or stop work under Doze, low battery, thermal pressure, network loss, standby restrictions, or explicit force-stop. Camera, microphone, and screen tools are unavailable to background Telegram/SMS/notification sessions and run only from explicit foreground, voice, or capture surfaces.

## Useful verification commands

```sh
./gradlew test lintDebug :app:assembleRelease
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.fsaint.androidagent.LockedFoldedRuntimeAcceptanceTest --no-daemon
./gradlew :app:releaseSha256
adb shell pidof com.fsaint.androidagent
```

Keep API keys out of source control, shell history, screenshots, and bug reports.
