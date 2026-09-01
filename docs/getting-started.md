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

1. Launch **Dark Lord**.
2. Grant microphone, camera, SMS, phone, and notification permissions when prompted.
3. Tap **Make Dark Lord your Assistant** and accept the role prompt. Then open Settings › Advanced features › Side button › **Press and hold** and choose the digital assistant so a long press of the side key opens Dark Lord. Confirm with `adb shell settings get secure assistant`.
4. Open **Communications settings**, provision your owner number, and grant SMS/dialer roles as needed.
5. Enable notification access for Dark Lord.
6. Return to the main screen and enter the OpenAI key in **OpenAI API key (owner only)**, then tap **Save model key**. The key is encrypted with Android Keystore and is not sent through SMS or diagnostics.
7. For reliable Telegram polling while the Flip is folded or locked, open the Android battery settings for Dark Lord, choose unrestricted battery/background usage, and keep the persistent runtime notification enabled. If notification permission or the **Agent runtime** channel is disabled, Dark Lord does not restore the background runtime; use **Open notification settings** in the app to re-enable it.

Detailed reset and Device Owner procedures are in the [device provisioning guide](device-provisioning/galaxy-z-flip3-reset-and-device-owner.md).

## First test

Send an SMS to the device, or invoke Dark Lord with the Side button, and ask a simple question such as `What is my battery level?` The model receives only the tools allowed by the active principal's scope. It may call a device tool and then sends one final response.

To test voice, hold the side key, speak the same request, and release. Dark Lord listens as soon as the assistant surface appears, finalizes when you stop talking (or when you tap the surface), and speaks the reply while showing it on the cover display when folded or on the main screen when open. Android does not report the side-key release to apps, so the end of your sentence is what sends the request. If no key is configured, Dark Lord returns a setup message instead of crashing. Use the [Stage 13 push-to-talk checklist](device-test/stage-13-side-key-push-to-talk.md) for the full sequence.

## Add an MCP server

1. Open **MCP server settings** from the main screen.
2. Enter a display name and an HTTPS Streamable HTTP endpoint, such as `https://mcp.example.com/mcp`.
3. If the server uses OAuth, enter its token endpoint and client ID. Never enter refresh tokens in ordinary fields or messages; those belong in the protected OAuth flow.
4. Tap **Save MCP server**. The configuration is stored in the encrypted Room database.
5. Grant the connection to the intended principal/scope before allowing model tool calls. Unknown principals do not receive configured MCP access.

The screen lists saved connections and lets the owner remove them. Real endpoint connectivity, OAuth enrollment, and Tailscale client enrollment remain deployment-specific device checks; use the [Stage 8 checklist](device-test/stage-8-mcp-skills.md) when validating those paths.

Dark Lord's inbound MCP server is currently a scoped protocol foundation (`TailscaleMcpServer`) rather than an always-on public endpoint. A live Tailscale listener and endpoint advertisement are still required before another MCP client can connect to the phone.

Use the [Stage 11 conversational harness checklist](device-test/stage-11-conversational-harness.md) for the complete smoke test. The [acceptance checklist](acceptance/flip3-prototype-checklist.md) tracks the broader device evidence still required.

Use the [Stage 12 background runtime checklist](device-test/stage-12-background-runtime.md) when validating folded and locked operation. Its automated instrumentation covers the foreground service, notification Stop/Restart actions, Telegram checkpointing, duplicate-start protection, notification gating, and a non-secure keyguard SMS/notification handler check. On the secure API 35 Flip, the class currently completes with four passes and one intentional keyguard skip so it cannot strand later tests behind the PIN screen. Physical hinge folding, secure-lock delivery, live owner Telegram/SMS delivery, real Android Notification Access delivery from another app, and force-stop/relaunch recovery remain manual device steps. The persistent foreground service keeps Telegram polling and queued work visible, but Android and Samsung policy can still delay or stop work under Doze, low battery, thermal pressure, network loss, standby restrictions, or explicit force-stop. Camera, microphone, and screen tools are unavailable to background Telegram/SMS/notification sessions and run only from explicit foreground, voice, or capture surfaces.

## Useful verification commands

```sh
./gradlew test lintDebug :app:assembleRelease
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.fsaint.androidagent.LockedFoldedRuntimeAcceptanceTest --no-daemon
./gradlew :app:releaseSha256
adb shell pidof com.fsaint.androidagent
```

Keep API keys out of source control, shell history, screenshots, and bug reports.
