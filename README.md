<p align="center">
  <img src="dark_lord.png" alt="Dark Lord" width="160">
</p>

# Android Agent

Android Agent is a sideloaded, no-root Kotlin prototype that turns a dedicated Android phone into a scoped, multi-principal agent platform. It exposes device capabilities, events, skills, and MCP connections to an OpenAI-powered runtime while enforcing authorization below the model.

The primary development device is a Samsung Galaxy Z Flip3 on Android 15. The prototype includes a posture-aware Assistant experience: the Side-key Assistant gesture works with a full UI when open and a touch-enabled cover UI when closed.

## Status

Stage 9 recovery, scheduling, Device Owner wiring, and bounded diagnostics are implemented and verified with automated tests. Stage 8 MCP and declarative skill foundations are implemented and verified with JVM tests. Stage 7 device capabilities are implemented and verified on the primary development device, including app inspection, accessibility status/actions, screen capture, camera, microphone/audio, Bluetooth/Wi‑Fi, location, sensors, NFC, USB, contacts, and app-private files. Physical communication, network configuration, Device Owner provisioning, reboot recovery, and permission prompts remain explicit tester-operated procedures.

## Documentation

- [Technical specification](SPEC.md)
- [Approved architecture and design](docs/superpowers/specs/2026-08-29-android-agent-design.md)
- [Implementation plan](docs/superpowers/plans/2026-08-29-android-agent-prototype.md)
- [Galaxy Z Flip3 reset and Device Owner provisioning](docs/device-provisioning/galaxy-z-flip3-reset-and-device-owner.md)
- [Stage 6 communications device acceptance checklist](docs/device-test/stage-6-communications.md)
- [Stage 7 capability device acceptance checklist](docs/device-test/stage-7-capabilities.md)
- [Stage 8 MCP and skills operations checklist](docs/device-test/stage-8-mcp-skills.md)
- [Stage 9 recovery and diagnostics device checklist](docs/device-test/stage-9-recovery-diagnostics.md)

## Intended stack

Native Kotlin, Jetpack Compose, Room, Android Keystore, OpenAI Responses API, Streamable HTTP MCP with OAuth, and Tailscale for private inbound MCP access.
