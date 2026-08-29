# Android Agent Prototype Design

## Goal

Build a sideloaded, no-root Android 15 prototype for the Samsung Galaxy Z Flip3 (SM-F711U1) that operates as an autonomous, multi-principal agent platform. It must process device and communications events, enforce authority below the LLM, use Android and MCP tools, and interact through both the normal and cover displays.

## Constraints

- Native Kotlin application with Jetpack Compose and Room.
- Android 12+ support; the primary hardware target is the connected Galaxy Z Flip3 on Android 15 / API 35.
- The phone has an active SIM/eSIM and will be provisioned as the default SMS application, default dialer, default Assistant, and Device Owner after factory reset.
- The prototype is sideloaded and may use Developer Mode and ADB.
- The Android app calls the OpenAI Responses API directly. Its credential is Keystore-protected.
- External services are configured MCP servers usi:q
ng Streamable HTTP and OAuth; local `stdio` and legacy SSE transports are out of scope.
- The phone MCP server is reachable only through the owner's Tailscale network and uses enrolled client authentication.
- The agent runs as an Android-supported foreground service with a visible persistent notification and battery-optimization exemption.
- Owner actions execute without per-action confirmation. Platform role, permission, OAuth, screen-capture, and accessibility grants remain one-time system setup flows.
- Public Git and HTTP registries may provide unsigned *declarative* skills. Downloaded executable code is prohibited in v0.1; executable extensions ship only inside the signed APK.

## System structure

The app is a Gradle multi-module Kotlin project. `:app` owns Compose UI, setup, Android manifests, and dependency wiring. `:core` modules contain the platform-independent runtime; `:capabilities` modules wrap Android APIs; `:oem:samsung-flip3` isolates Samsung-specific behavior.

`AgentRuntime` is a persistent foreground service. It has no special knowledge of individual Android features. Capabilities implement a common contract: initialize, report status, publish `AgentEvent` values, and register typed `AgentTool` definitions. A capability can report unsupported hardware or OS restrictions without affecting the rest of the runtime.

The execution path is fixed:

```text
persist event -> resolve identity -> resolve scope -> load/create session
-> build permitted context -> select skills/tools -> plan -> scope-check tool/MCP call
-> execute -> verify -> persist audit/result -> reply, continue, or escalate
```

Events are durably stored before dispatch and are marked complete only after a terminal result is persisted. Recoverable work is retried through WorkManager; immediate event processing remains in the foreground runtime.

## Agent and authorization model

Principal resolution normalizes inbound phone numbers to E.164. First-run setup enrolls the owner with a manually entered E.164 number verified by a one-time SMS code. Owner administration is available through both a local settings screen and owner-authenticated SMS commands.

Every interaction has a `ScopedAgentSession` containing its principal, role, scope, channel, and memory namespace. The initial scope levels are OWNER, KNOWN, and UNKNOWN. Known principals may have role-specific grants for tools, skills, memory namespaces, contacts, files, and configured MCP servers.

`ScopedContextBuilder` filters data before it reaches the model. `ScopedToolRouter`, `ScopedMcpRouter`, `ScopedMemoryProvider`, and `ScopedSkillRegistry` independently enforce the same grants at execution time. The LLM cannot obtain an unavailable capability by prompt or tool-name construction.

Known and unknown sessions can create durable escalations using `owner.ask`. An escalation records source session, question, reason, status, and proposed action; an owner response resumes the original session asynchronously and sends its reply.

## Storage and secrets

Room stores principals, scope grants, sessions, conversations, events, event delivery states, scheduled tasks, capability status, MCP configurations, OAuth metadata, skills, skill versions, update attempts, escalations, tool executions, verification outcomes, and audit records.

Memory is logically partitioned by principal and shared namespace. An encrypted-memory layer protects persisted content. Android Keystore protects the database encryption key envelope, OpenAI credential, Tailscale/MCP client material, and OAuth refresh tokens. Access logs do not record raw secrets.

## Model, tools, skills, and MCP

The OpenAI provider is an interface owned by `:core:agent`; tools and capabilities do not call it directly. The production adapter targets the Responses API and supports text, image, and voice-request context. A deterministic fake provider drives tests.

Tools are typed, expose structured schemas, and return a common result containing success, error code, recoverability, message, payload, and verification state. Required error codes include `UNSUPPORTED`, `NOT_FOUND`, `PERMISSION_REQUIRED`, `SCOPE_DENIED`, `USER_CONFIRMATION_REQUIRED`, `DEVICE_BUSY`, `TIMEOUT`, `NETWORK_ERROR`, `APP_NOT_RUNNING`, `SECURE_WINDOW`, and `OS_RESTRICTED`.

The app is both an MCP client and server. Outbound connections use Streamable HTTP and OAuth authorization flows. Each configuration belongs to allowed scopes and is filtered before being advertised to the model. The inbound server binds only to the Tailscale-reachable interface, authenticates an enrolled client identity, and publishes the permitted `android.*` tools.

Downloaded skills contain a manifest, natural-language instructions, examples, and tests. Installation downloads to internal storage, validates schema and hashes, executes restricted dry-run tests using fake tools, persists a versioned candidate, and atomically activates it. Failed update validation retains the previous active skill and supports explicit rollback. Downloaded skill instructions never gain filesystem, network, reflection, or direct Android access: all actions remain routed through authorized tools.

## Android capabilities

MVP implementation is incremental, but the final prototype provides independent capability modules for device state, applications, accessibility UI, screen capture, camera, notifications, SMS, telephony, Bluetooth/BLE, Wi-Fi, microphone/audio, location, sensors, NFC, USB, contacts, and permitted files.

SMS uses the default-SMS role and emits persisted received/sent/delivery events. Telephony uses the default-dialer role plus Telecom/InCallService and exposes call control while explicitly reporting that two-way PSTN audio capture is unavailable. Accessibility is manually enabled during setup. Screen capture uses MediaProjection and must report `SECURE_WINDOW` when Android blocks capture.

## Samsung Flip3 assistant experience

The Flip3 adapter detects device posture, discovers the cover display and cover touch support, and reports a `flip3.coverUi` capability only when the hardware is available. It must not make Samsung assumptions in the core runtime.

The app qualifies for and requests Android's Assistant role using `VoiceInteractionService` and `VoiceInteractionSession`. This replaces Gemini as the selected assistant after the user accepts the one-time Android role prompt. The system Assistant gesture (the Side/Power-key long press) invokes the agent through the supported Assistant pathway.

When closed, the session renders a 512x260 Compose cover UI on the external display, accepts cover touch input, captures speech through the running microphone service, transcribes the request, and shows/transmits a compact response. When open, the same session renders a full-screen assistant UI. Sessions preserve task state through open/close transitions and re-render for the active display. If cover presentation launch or input is unavailable on a device build, the capability reports unsupported and the session continues on the normal display and audio channel.

## Operations, policy, and failure handling

The app requests the required runtime permissions and role grants through a guided first-run flow. Device Owner provisioning is an explicit post-reset setup task for this dedicated agent phone. Boot completion restores principals, scopes, active skills, schedules, pending work, capability initialization, MCP connectivity, and the foreground runtime.

Every autonomous operation appends an audit entry that includes event, principal, scope, session, selected skill, tool, authorization decision, result, and verification result. The development UI includes event injection/monitoring, capability and permission inspection, scope/principal inspection, MCP and skill inspection, memory inspection, tool invocation inspection, agent trace, and audit search/export.

No operation may falsely report completion. Failures are converted to a structured tool result, then a retry, permitted fallback, owner escalation, or transparent response. Scope denial never triggers a fallback that accesses a broader resource.

## Delivery and validation

1. Create the core project, Room stores, encrypted secrets, audit/event pipeline, capability and tool registries, fake runtime, and OpenAI provider.
2. Deliver an end-to-end owner slice: Assistant role, Flip3 open/closed sessions, microphone/STT, battery and camera tools, model reasoning, and SMS reply.
3. Add default SMS/dialer capabilities, principal/scope routing, hard authorization, notifications, and durable owner escalation/resumption.
4. Add the remaining Android capabilities in coherent, independently tested groups.
5. Add Streamable HTTP MCP/OAuth, Tailscale MCP server, declarative skill lifecycle, scheduler, Device Owner provisioning, and Samsung/OEM adapters.
6. Run the 28 prototype acceptance scenarios from the source specification plus Flip3 Assistant invocation in both postures, cover touch, spoken request/response, reboot recovery, and permission/scope denial paths.

Unit tests use fakes for every Android adapter and provider. Integration tests validate Room-backed durability and scope enforcement. Instrumentation tests and an explicit real-device checklist validate the Flip3, active SIM/eSIM, default roles, hardware, network, reboot, and Tailscale paths.
