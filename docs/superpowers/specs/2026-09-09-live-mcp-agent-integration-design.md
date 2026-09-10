# Live MCP tools in the Dark Lord agent

Status: base integration requested and approved in conversation; detailed design awaiting review.

## Outcome and scope

A saved, reachable, unauthenticated HTTPS MCP server becomes usable by the owner agent. The app discovers its tools, supplies their names, descriptions and input schemas to the conversational model, executes selected calls, and returns real results. Existing saved servers are reused; the owner need not add them again.

This change covers outbound Streamable HTTP connections. OAuth, arbitrary authorization headers, stdio subprocesses, legacy two-endpoint HTTP+SSE servers, inbound MCP hosting, remote prompts/resources, sampling, elicitation and asynchronous MCP tasks remain separate features. An authentication challenge is shown as authentication required, not as a successful connection. No physical installation or live external tool invocation is implied by implementation.

## Current failure

`DarkLordApplication.addMcpServer` saves an endpoint and puts only its UUID in `mcpCatalog`. `ScopedContextBuilder` filters those IDs, and `OpenAiResponsesProvider` mentions them in text. Neither discovers remote tools. The live `agentTools` dispatcher contains phone, Python, browser, artifact, job and Telegram handlers, but no MCP execution route.

The current `StreamableHttpMcpClient` is a foundation, not a complete transport: it lacks initialization/discovery and emits a simplified call body. Its existence does not mean the app can connect. Owner authorization already permits MCP resources; adding an owner grant will not repair the missing execution path.

## Approaches

1. **Recommended: discovered native model tools.** Expose a bounded, per-request snapshot of remote tool definitions alongside phone tools. Keep a stable mapping back to server ID and original tool name. This makes remote tools directly selectable and gives the model their actual parameter schemas.
2. **Generic list/call bridge.** Give the agent a few MCP management tools and make it fetch schemas before calling by name. This reduces dynamic catalog changes, but consumes more of the eight-turn budget and makes tool selection less direct. It is a possible later fallback for very large catalogs.
3. **Prompt-only server descriptions.** Not sufficient: names and URLs in context cannot create an execution route. Do not retain this as an apparent working integration.

## Connection and discovery

Add an application-owned connection manager backed by the existing saved configurations. Keep network operations on IO, cancellable and bounded. Reuse the existing HTTP transport interface where practical, extending it with response headers and streaming access rather than reading an indefinite SSE response into a string.

Implement JSON-RPC initialization, the initialized notification, paginated tool discovery and tool calls. Start with explicitly tested protocol versions 2025-11-25, 2025-06-18 and 2025-03-26; reject unsupported negotiation rather than assuming compatibility. Preserve the negotiated version and any session header. Support JSON responses and POST-response SSE, stopping when the matching response arrives. Handle notifications without treating them as results; reject unsupported server requests safely. These behaviors follow the [MCP transport](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports) and [lifecycle](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle) contracts.

Use per-server locking for initialization/cache updates, not a single global lock. Revalidate configuration after suspensions so removed servers cannot be republished by late discovery. Cache successful catalogs for five minutes; refresh on explicit Refresh, configuration changes, or a list-changed notification observed during an active response. Rediscover after process restart. Do not promise a persistent notification subscription in this first increment.

Initial limits: 10 seconds per discovery operation, 20 seconds aggregate discovery per conversation, 60 seconds per tool call, 10 discovery pages, 64 tools per server, 128 remote tools total, 32 KiB per schema and 1 MiB per protocol response. Reject repeated cursors and malformed data. Enforce byte/time limits while reading, including SSE. Show truncation or unsupported-schema omissions explicitly. These are configurable implementation constants, not silent truncation of argument data.

A failed server must not remove phone tools or prevent an ordinary conversation. Retry discovery on Refresh or a later eligible request. On session expiry, establish a new session for subsequent work; do not silently replay a previously dispatched tool call. Lost responses have unknown outcomes, not proof of failure or rollback.

## Agent integration and types

Enrich each conversational request before its first model call with a snapshot of authorized, discovered MCP tool definitions. Put the enrichment at a shared harness boundary used by Telegram, SMS, local API, typed chats, photo and voice. Existing phone-only constructors and tests retain backward-compatible defaults.

Use deterministic, provider-safe aliases derived from server ID plus original tool name, with collision detection and an explicit reverse map. Never infer the destination URL from a model-generated alias. Include the saved server display name in descriptions and the inventory; opaque UUIDs alone are not a usable inventory.

Preserve each tool's JSON input schema, including required properties, enums, arrays and nested objects, as described by the [MCP tools contract](https://modelcontextprotocol.io/specification/2025-11-25/server/tools). Do not flatten every argument to a string or replace unknown schemas with an empty schema. Reject unsupported schema forms with a visible diagnostic instead of making a tool appear callable. Do not fetch remote schema references.

Avoid a repository-wide rewrite of phone tool arguments. For MCP-only aliases, retain the parsed argument object as canonical JSON in an internal, reserved `ToolCall.arguments` envelope. Phone handlers keep their existing string-map contract. The remote dispatcher parses this envelope back to the original typed object. The envelope is not a model-facing parameter. Update checkpoint encoding to structured, versioned JSON with a legacy reader, because its current ampersand-delimited encoding cannot safely preserve nested JSON or strings containing ampersands. Effect identity must include the destination server, original tool name and canonical arguments.

Dispatch MCP calls inside the existing harness budget, cancellation, effect reservation and history machinery. Check the request snapshot and current connection authorization before network execution. Do not automatically repeat remote side effects after timeout, cancellation, restart or ambiguous transport failure. Preserve completed and unknown outcomes in the existing audit/history path.

## Authorization and untrusted content

Owner sessions can use configured servers. Non-owner sessions discover or execute a server only when the existing MCP resource grant permits it; phone-tool grants alone cannot authorize MCP. Enforce this below the model, on every call, including forged aliases and removed configurations. This release does not introduce cross-principal OAuth credential sharing.

Only owner-configured HTTPS endpoints are contacted. Permit private/Tailscale hosts with normal certificate verification, preserving the intended private-server use case. Reject URLs with userinfo/fragments, avoid automatic cross-origin redirects, and never disable TLS validation. Model tool arguments cannot alter the configured transport destination. Saved OAuth fields are retained but not advertised as working authentication.

Treat remote descriptions and results as untrusted tool data, not system instructions or proof that an action is safe. Surface text and structured results within bounds, honoring `isError`. Never fetch resource links automatically. For binary content blocks, report their type and that delivery is unsupported in this increment; do not flood prompts or logs with base64. Tokens, session identifiers, raw response bodies and endpoint query secrets stay out of diagnostics.

## Settings and feedback

The saved-server list gains connection state, last checked time, discovered tool count/list, and a Refresh action. Distinguish Saved/not checked, Connecting, Ready, No tools, Authentication required, Unsupported and Unreachable. Saving does not mean Ready. Removing a server immediately invalidates its catalog and future dispatch eligibility without deleting unrelated settings or chats.

Retain existing configuration encoding on read. If a new status/config representation is needed, migrate non-destructively. README and getting-started must describe exactly the implemented transport and authentication limitations.

## Verification

Use a deterministic fake MCP server/transport to test the entire saved configuration → discovery → outgoing model tool definitions → selected call → returned result path. Prove nested objects, arrays, numbers, booleans, nulls, punctuation and empty objects survive model parsing, checkpoint round trips and HTTP serialization.

Cover JSON and SSE responses, matching request IDs, session/version headers, pagination, malformed/oversized input, unsupported versions, missing tools capability, authentication errors, cancellation, reconnect without effect replay, alias collisions, catalog removal races, and isolation of one failed server. Verify no network call for unauthorized or forged requests. Regression-test ordinary phone tools and all conversational entry points through the shared enrichment boundary.

Run full unit tests, lint and APK assembly. Device checks follow the project's device-testing skill, use non-destructive installation only when requested, and never run Gradle connected tests on the configured phone. Live acceptance requires the saved server to be reachable: first discover/list only, then use an explicitly approved read-only tool through the phone chat API and retain a sanitized report. A mock pass must not be reported as a pass against the user's server.

## Review and next step

Review this design, then write and execute the implementation plan. The earlier persistent-chat work remains untouched. Do not include those unrelated changes in the design commit or push anything.
