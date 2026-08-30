<p align="center">
  <img src="dark_lord.png" alt="Dark Lord" width="160">
</p>

# Android Agent

Android Agent is a sideloaded, no-root Kotlin prototype that turns a dedicated Android phone into a scoped, multi-principal agent platform. It exposes device capabilities, events, skills, and MCP connections to an OpenAI-powered runtime while enforcing authorization below the model.

The primary development device is a Samsung Galaxy Z Flip3 on Android 15. The prototype includes a posture-aware Assistant experience: the Side-key Assistant gesture works with a full UI when open and a touch-enabled cover UI when closed.

## Status

The Stage 6 communications implementation has automated verification on the primary development device. Physical SMS, call, notification, and escalation acceptance remains an explicit tester-operated procedure.

## Documentation

- [Technical specification](SPEC.md)
- [Approved architecture and design](docs/superpowers/specs/2026-08-29-android-agent-design.md)
- [Implementation plan](docs/superpowers/plans/2026-08-29-android-agent-prototype.md)
- [Galaxy Z Flip3 reset and Device Owner provisioning](docs/device-provisioning/galaxy-z-flip3-reset-and-device-owner.md)
- [Stage 6 communications device acceptance checklist](docs/device-test/stage-6-communications.md)

## Intended stack

Native Kotlin, Jetpack Compose, Room, Android Keystore, OpenAI Responses API, Streamable HTTP MCP with OAuth, and Tailscale for private inbound MCP access.
