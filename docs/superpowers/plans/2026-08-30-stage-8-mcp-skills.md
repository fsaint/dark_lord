# Stage 8 MCP and Declarative Skills Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add scoped outbound MCP connections, OAuth token lifecycle, private inbound Tailscale transport, and safe declarative skill installation with atomic rollback.

**Architecture:** Keep protocol and package validation in Android-free `:core:mcp` and `:core:skills` modules. Every MCP connection and skill is filtered through existing `ScopeRegistry`; transport adapters expose typed results and never bypass authorization. Skill packages are data-only manifests/instructions/examples/tests, validated and activated transactionally.

**Tech Stack:** Kotlin/JVM, Java `HttpClient`/transport seams, kotlinx-coroutines, JUnit 5, Android Keystore adapter boundary, Room repositories where persistence is required.

**Spec:** `docs/superpowers/specs/2026-08-29-android-agent-design.md` (Model, tools, skills, and MCP; Storage and secrets; Operations, policy, and failure handling)

## Global Constraints

- Streamable HTTP and OAuth are the only outbound MCP transports; local `stdio` and legacy SSE are out of scope.
- Inbound MCP listens only on the configured Tailscale-reachable interface and requires enrolled client authentication.
- Refresh tokens and client material cross an Android Keystore-protected secret-store boundary; tests use a fake store.
- Every advertised or executed MCP tool is scope-checked below the model.
- Downloaded skills are declarative only: reject executable payloads, unsafe archive paths, invalid manifests, and hash mismatches.
- Failed skill updates leave the previous active version unchanged and retain a rollback candidate.

### Task 1: Lock down scoped MCP discovery and transport contracts

**Files:**
- Modify: `core/mcp/build.gradle.kts`, `settings.gradle.kts`
- Create: `core/mcp/src/main/kotlin/com/fsaint/androidagent/mcp/McpContracts.kt`
- Create: `core/mcp/src/main/kotlin/com/fsaint/androidagent/mcp/ScopedMcpRouter.kt`
- Test: `core/mcp/src/test/kotlin/com/fsaint/androidagent/mcp/ScopedMcpRouterTest.kt`

- [ ] Write tests proving an ungranted principal cannot discover or call a personal connection, while an owner can access an enrolled connection.
- [ ] Add typed connection/tool/result models and a router that filters both discovery and execution through `ScopeRegistry`.
- [ ] Return `SCOPE_DENIED`, `NOT_FOUND`, and `NETWORK_ERROR` without leaking connection credentials.
- [ ] Run `./gradlew :core:mcp:test` and commit `feat: add scoped MCP contracts`.

### Task 2: Implement Streamable HTTP and OAuth lifecycle

**Files:**
- Create: `core/mcp/src/main/kotlin/com/fsaint/androidagent/mcp/StreamableHttpMcpClient.kt`
- Create: `core/mcp/src/main/kotlin/com/fsaint/androidagent/mcp/OAuthManager.kt`
- Create: `core/mcp/src/test/kotlin/com/fsaint/androidagent/mcp/OAuthManagerTest.kt`

- [ ] Test authorization-code exchange, refresh, expiry, and secret-store failure using fake HTTP and secret-store adapters.
- [ ] Implement JSON request/response transport with bounded body sizes, HTTPS-only URLs, timeouts, and structured network errors.
- [ ] Store only opaque token references/metadata in regular state; route refresh tokens through the secret-store interface.
- [ ] Run MCP unit tests and commit `feat: add Streamable HTTP OAuth client`.

### Task 3: Add private inbound Tailscale MCP server

**Files:**
- Create: `core/mcp/src/main/kotlin/com/fsaint/androidagent/mcp/TailscaleMcpServer.kt`
- Create: `core/mcp/src/test/kotlin/com/fsaint/androidagent/mcp/TailscaleMcpServerTest.kt`
- Modify: `app/src/main/...` only if application wiring is needed after core tests

- [ ] Test rejection of non-Tailscale addresses, unknown client identities, malformed requests, and out-of-scope tools.
- [ ] Implement an injectable listener/authenticator boundary that binds only to the configured interface and delegates authorized calls to `ScopedMcpRouter`.
- [ ] Bound request size, concurrent work, and response size; do not expose credentials or unfiltered tool lists.
- [ ] Run MCP tests and commit `feat: add private inbound MCP server`.

### Task 4: Validate declarative skill packages

**Files:**
- Create: `core/skills/src/main/kotlin/com/fsaint/androidagent/skills/SkillContracts.kt`
- Create: `core/skills/src/main/kotlin/com/fsaint/androidagent/skills/SkillPackageValidator.kt`
- Test: `core/skills/src/test/kotlin/com/fsaint/androidagent/skills/SkillPackageValidatorTest.kt`

- [ ] Test valid manifests, archive hash mismatch, path traversal, executable payloads, missing required files, oversized files, and malformed YAML.
- [ ] Implement strict manifest parsing and SHA-256 archive verification with an allowlist of declarative files (`manifest.yaml`, `instructions.md`, `examples/`, `tests/`).
- [ ] Produce typed validation failures without extracting untrusted paths outside an internal staging directory.
- [ ] Run skills unit tests and commit `feat: validate declarative skill packages`.

### Task 5: Install, activate, update, and roll back skills atomically

**Files:**
- Create: `core/skills/src/main/kotlin/com/fsaint/androidagent/skills/SkillInstaller.kt`
- Create: `core/skills/src/main/kotlin/com/fsaint/androidagent/skills/SkillUpdateService.kt`
- Test: `core/skills/src/test/kotlin/com/fsaint/androidagent/skills/SkillRollbackTest.kt`

- [ ] Test that a failed update leaves the prior active version unchanged and that a successful update atomically switches versions.
- [ ] Implement fake-tool dry runs for examples/tests, versioned candidates, update-attempt records, active-version pointers, and explicit rollback.
- [ ] Ensure cancellation or process failure cannot expose a partially activated version.
- [ ] Run skills tests and commit `feat: add declarative skill lifecycle`.

### Task 6: Integrate and verify Stage 8

- [ ] Wire module dependencies and application registries without bypassing policy routers.
- [ ] Run `./gradlew :core:mcp:test :core:skills:test :app:testDebugUnitTest :app:lintDebug`.
- [ ] Run connected smoke checks for status/error reporting where Android wiring exists; record unsupported/network-required outcomes truthfully.
- [ ] Update README and the Stage 8 device/operations checklist, run `git diff --check`, commit documentation, and push `master`.

