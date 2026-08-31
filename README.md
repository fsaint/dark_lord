<p align="center">
  <img src="dark_lord.png" alt="Dark Lord" width="160">
</p>

# Android Agent

Android Agent is a sideloaded, no-root Kotlin prototype that turns a dedicated Android phone into a scoped, multi-principal agent platform. It exposes device capabilities, events, skills, and MCP connections to an OpenAI-powered runtime while enforcing authorization below the model.

The primary development device is a Samsung Galaxy Z Flip3 on Android 15. The prototype includes a posture-aware Assistant experience: the Side-key Assistant gesture works with a full UI when open and a touch-enabled cover UI when closed.

## Status

The conversational harness and Stage 12 background-runtime acceptance coverage are implemented. Focused unit checks pass, while connected folded/locked acceptance needs an unlocked SM-F711U1 for setup and operator evidence for live carrier, Telegram, notification-listener, force-stop, external MCP/Tailscale, posture, and voice flows.

## Features

- Side-button Assistant invocation with posture-aware open-screen and cover-screen experiences.
- SMS receive, send, reply, delivery evidence, owner commands, and communications administration.
- Dialer, incoming-call UI, CallKit-style in-call integration, and voice interaction services.
- Notification listener and notification event ingestion.
- Voice capture with Android SpeechRecognizer and spoken responses with TextToSpeech.
- Device tools for battery/status, installed apps, camera, microphone, audio, radios, location and environment, sensors, NFC, USB, contacts, private files, and screen capture.
- Scoped authorization by owner, known principal, and unknown principal, with scope-denied responses and escalation support.
- Owner provisioning, principal management, capability permission flows, Device Owner support, reboot recovery, scheduling, audit records, and bounded diagnostics.
- Visible foreground runtime service for Telegram polling and queued work, with persistent Stop/Restart notification actions and boot/process recovery where Android permits it.
- OpenAI Responses API conversational harness with model-selected tools, an eight-turn safety budget, one final SMS/voice response, and resumable Room-backed checkpoints.
- Owner-only OpenAI API-key setup stored in Android Keystore; credentials are excluded from diagnostics, messages, and audit output.
- MCP connection foundations with scoped discovery, OAuth metadata, Streamable HTTP seams, private Tailscale server support, and network failure handling.
- Owner-facing MCP server settings for saving and removing HTTPS endpoints with optional OAuth configuration.
- Declarative skill manifests, validation, installation lifecycle, versioning, rollback, and scoped skill access.
- Release signing, APK checksums, sideloading instructions, automated JVM/app tests, lint, and connected-device acceptance tests.

## Documentation

- **[Getting started](docs/getting-started.md)** — build, install, provision, configure the model, and run the first SMS/voice test.

- [Technical specification](SPEC.md)
- [Approved architecture and design](docs/superpowers/specs/2026-08-29-android-agent-design.md)
- [Implementation plan](docs/superpowers/plans/2026-08-29-android-agent-prototype.md)
- [Galaxy Z Flip3 reset and Device Owner provisioning](docs/device-provisioning/galaxy-z-flip3-reset-and-device-owner.md)
- [Stage 6 communications device acceptance checklist](docs/device-test/stage-6-communications.md)
- [Stage 7 capability device acceptance checklist](docs/device-test/stage-7-capabilities.md)
- [Stage 8 MCP and skills operations checklist](docs/device-test/stage-8-mcp-skills.md)
- [Stage 9 recovery and diagnostics device checklist](docs/device-test/stage-9-recovery-diagnostics.md)
- [Stage 12 folded and locked background runtime checklist](docs/device-test/stage-12-background-runtime.md)
- [Flip3 prototype acceptance checklist](docs/acceptance/flip3-prototype-checklist.md)
- [Prototype sideloading guide](docs/release/sideloading.md)

## Intended stack

Native Kotlin, Jetpack Compose, Room, Android Keystore, OpenAI Responses API, Streamable HTTP MCP with OAuth, and Tailscale for private inbound MCP access.
