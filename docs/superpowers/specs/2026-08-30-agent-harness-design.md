# Dark Lord Agent Harness Specification

## Purpose

The harness is the single execution engine behind Dark Lord’s Side-button Assistant, SMS, voice, notifications, scheduled work, and external integrations. It gives the model a coherent interface to phone capabilities, MCP servers, and declarative skills while ensuring that authorization, secrets, verification, and durable state remain outside model control.

The design is informed by Hermes Agent’s explicit toolsets, persistent memory and session search, skills that improve through use, scheduled automation, multi-channel gateway, and interruptible conversations. See the [Hermes Agent project](https://github.com/nousresearch/hermes-agent) for that inspiration.

## Goals

- Provide one resumable agent loop for every interaction channel.
- Expose all permitted Android capabilities, MCP tools, and skills through typed, scope-filtered definitions.
- Let the model select and sequence tools, but never authorize itself.
- Support owner autonomy inside granted scope and escalation for non-owner or out-of-scope actions.
- Preserve conversations, tool results, checkpoints, memory, and audit records across process death and reboot.
- Return one coherent final response over SMS, voice, Assistant UI, or notification.
- Make tool execution observable, bounded, cancellable, and testable with deterministic fakes.

## Non-goals for v1

- Arbitrary downloaded executable code or plugins.
- Direct model access to Android APIs, filesystem paths, sockets, credentials, or SQL.
- Unbounded autonomous loops or silent privilege expansion.
- PSTN audio interception, root-only APIs, or bypassing Android role/permission prompts.
- Multi-agent delegation before the single-agent loop is reliable.

## Runtime architecture

```text
Channel adapter
  -> EventNormalizer
  -> IdentityResolver
  -> ScopedSessionManager
  -> ContextAssembler
  -> AgentHarness
       -> ModelProvider (Responses API)
       -> ToolCatalog (Android + MCP + skills)
       -> PolicyGate (authorization + confirmation)
       -> ExecutionSupervisor (timeouts, cancellation, concurrency)
       -> CheckpointStore / ConversationStore
       -> AuditStore / TraceSink
  -> ResponseRouter (SMS, TTS, Assistant UI, notification)
```

The harness depends only on Android-free interfaces. `:app` supplies Android adapters; `:core:policy` remains the enforcement point; `:core:data` supplies encrypted Room persistence.

## Turn lifecycle

Each request is a durable `AgentRun` identified by session and event IDs.

1. **Accept** — persist the inbound event and create or resume the session.
2. **Resolve** — normalize the source, resolve the principal, and calculate the immutable scope snapshot for this run.
3. **Assemble context** — load bounded conversation history, permitted memory, active skill instructions, and scope-filtered tool definitions.
4. **Model turn** — send the user input and context to the Responses API. The model may return text, one or more function calls, or an explicit escalation request.
5. **Validate** — reject unknown tools, malformed arguments, disallowed resources, oversized payloads, and calls outside the scope snapshot.
6. **Execute** — run up to four independent tool calls in parallel; serialize calls that declare the same resource key. Enforce per-tool timeout and cancellation.
7. **Persist** — persist each tool call and result before sending the next model turn. Completed calls are never repeated during resume.
8. **Continue** — provide structured tool results to the model and repeat from step 4, up to eight model turns per run.
9. **Finalize** — persist the final assistant response, verification state, and trace; send exactly one channel response.
10. **Recover or escalate** — on cancellation, save a checkpoint; on recoverable failure, retry within policy; on denied authority, create an escalation or return a transparent denial.

The harness must distinguish `FINAL`, `TOOL_CALL`, `ESCALATE`, `CANCELLED`, `TURN_LIMIT`, and `FAILED` terminal states. Cancellation exceptions propagate to the supervisor and are not converted into successful replies.

## Core contracts

```kotlin
interface AgentHarness {
    suspend fun run(request: AgentRequest): AgentRunResult
    suspend fun resume(runId: String): AgentRunResult
    suspend fun cancel(runId: String)
}

interface ModelProvider {
    suspend fun respond(request: ModelRequest): ModelResponse
}

interface ToolProvider {
    suspend fun discover(scope: ScopeSnapshot): List<ToolDefinition>
    suspend fun execute(call: ValidatedToolCall): ToolResult
}

interface SkillProvider {
    suspend fun activeSkills(scope: ScopeSnapshot): List<SkillDefinition>
}
```

`ToolDefinition` includes a stable ID, description, JSON argument schema, capability source, required resource grants, confirmation policy, timeout, and concurrency key. `ToolResult` includes success, structured payload, error code, recoverability, verification state, and a safe user-facing summary.

## Tool catalog

The catalog merges three sources before every run:

### Android capabilities

Device status, apps, camera, microphone/audio, radios, environment/location, sensors, NFC, USB, contacts, private files, screen capture, notifications, SMS, telephony, accessibility, and Flip3 posture/display tools are registered through the existing capability contract.

### MCP servers

Saved HTTPS Streamable HTTP connections are loaded from Room. The MCP client performs discovery, filters tools by the session’s MCP grants, and exposes namespaced definitions such as `mcp.<connection>.<tool>`. OAuth access tokens are obtained/refreshed by the secret boundary and never enter model context. Calls go through `ScopedMcpRouter` and `StreamableHttpMcpClient`, with network, size, timeout, and protocol errors normalized into `ToolResult`.

### Declarative skills

An active skill contributes instructions, examples, and references—not executable code. Skill instructions can recommend tools and compose procedures, but every resulting call still passes through the same catalog and policy gate. Skill activation is versioned and atomic; failed validation leaves the previous version active.

## Authorization and safety

Authorization is evaluated below the model at discovery and execution time.

- The scope snapshot is derived from the resolved principal and cannot be changed by a model response.
- Owner actions execute autonomously only when the owner already has the required grant and Android has granted the underlying role/permission.
- Known principals receive only explicit tool, MCP, memory, file, contact, and skill grants.
- Unknown principals receive the minimal default set and may create owner escalations.
- High-impact operations declare `USER_CONFIRMATION_REQUIRED` or `OWNER_APPROVAL_REQUIRED` regardless of model wording.
- A scope denial is terminal for that call; fallback logic may not search for a broader tool or alternate credential.
- Tool arguments are schema-validated, size-limited, and redacted before audit persistence.

## Conversation, memory, and learning

Conversation messages and run checkpoints are stored per session in encrypted Room. The context assembler applies a token/byte budget, keeps the current user request and pending tool results, and summarizes older turns into durable session memory when necessary.

Memory has separate namespaces for the principal, the session, and explicitly shared owner data. Reads and writes require memory grants. A future learning loop may propose a new declarative skill or memory entry, but activation requires validation and owner policy; the model cannot silently rewrite its own policy.

## Channels and responses

All channels create the same `AgentRequest` shape. Channel adapters provide source identity, text/transcript, reply target, presentation constraints, and cancellation signal.

- **SMS:** one final reply, bounded to carrier-safe length; progress stays in trace/audit.
- **Voice:** SpeechRecognizer transcript enters the same run; final text is sent to TextToSpeech.
- **Assistant UI:** open/cover surfaces render a compact live state and final response.
- **Notifications/schedules:** events enter the same queue and use owner escalation or notification delivery according to policy.

The response router never leaks raw tool payloads, credentials, stack traces, or internal prompts.

## Persistence and recovery

Room persists `AgentRun`, run state, conversation messages, tool calls/results, selected skills, scope snapshot, and terminal response. On process death or boot:

1. Restore principals and grants.
2. Restore active skills and MCP metadata.
3. Requeue runs in `ACCEPTED`, `MODEL_WAITING`, or `TOOL_RUNNING` states.
4. Mark in-flight calls unknown unless their idempotency key proves completion.
5. Resume from the last persisted model/tool boundary.

Every tool call has an idempotency key derived from run ID, turn, tool ID, and arguments hash. Non-idempotent tools must declare that they cannot be automatically retried.

## Model provider

The first provider is OpenAI Responses API over HTTPS. Requests contain the user input, bounded transcript, active skill instructions, and only permitted tool definitions. Function calls are parsed into `ValidatedToolCall`; assistant text becomes a final response. Provider failures become structured `NETWORK_ERROR`, `TIMEOUT`, or `PERMISSION_REQUIRED` outcomes and never expose the API key.

The provider interface allows later local, alternate-provider, streaming, and realtime voice implementations without changing the harness or capability modules.

## Observability

Each run emits a bounded trace with run ID, session, principal role, turn count, selected skill IDs, tool IDs, durations, authorization decisions, verification states, and terminal reason. Sensitive arguments and all credentials are redacted. Diagnostics expose health and counts, not prompts, tokens, OAuth refresh tokens, or private file contents.

## Testing requirements

- Harness unit tests for final responses, multi-tool sequencing, parallelism limits, turn limits, cancellation, resume, malformed model output, and provider failure.
- Policy tests proving unavailable Android/MCP/skill tools are absent from context and denied at execution.
- Room integration tests proving checkpoints survive process recreation and completed tools are not repeated.
- MCP tests for discovery, OAuth refresh, protocol errors, size limits, and scope denial.
- Android tests for SMS, Side-button Assistant, voice transcript/TTS routing, notification events, and cover/open response rendering.
- Device acceptance tests for owner setup, key storage, real MCP endpoint enrollment, Tailscale client authentication, reboot recovery, and permission refusal.

## Delivery stages

1. Replace the current one-shot runtime path with `AgentHarness` and durable `AgentRun` state.
2. Adapt every Android capability to typed tool definitions and verified results.
3. Wire saved MCP connections into discovery and execution, including OAuth refresh.
4. Wire declarative skills into context assembly and tool planning.
5. Add memory compaction, session search, scheduling, and interruption/resume UX.
6. Add the live Tailscale inbound MCP listener and advertise the endpoint only after authenticated enrollment.
7. Validate the complete SMS, voice, Assistant, MCP, skills, recovery, and scope-isolation matrix on device.

## Acceptance criteria

The harness is ready for production-prototype testing when a single owner can ask by SMS or voice for an action that requires an Android tool, an MCP tool, and a skill procedure in one conversation; the agent selects and executes only permitted tools, survives process death without repeating completed work, returns one final response, and produces an auditable trace with no secret leakage. A known or unknown principal attempting the same request must receive a scope denial or owner escalation without any unauthorized tool call.
