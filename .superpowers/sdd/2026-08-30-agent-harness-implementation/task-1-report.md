# Task 1 report: durable agent harness contracts

Implemented the Android-free harness contract layer.

## Changes

- Added `AgentRequest`, `ModelRequest`, sealed `ModelResponse`, `ScopeSnapshot`, `SkillDefinition`, run results/states, and the `AgentHarness` lifecycle interface.
- Added validated `ToolDefinition`, `ToolProvider`, `ToolCatalog`, confirmation policy, and safe tool error enums.
- Extended `ModelProvider` with the conversational `respond` entry point while retaining the existing one-shot `plan` API through compatibility defaults.
- Added focused tests covering response variants, definition validation, invalid tool IDs, stable terminal-state serialization, and safety budgets.

## Verification

- `./gradlew :core:runtime:test --tests '*AgentContractsTest'` — passed.
- `./gradlew :core:runtime:compileKotlin` — passed.
- `./gradlew :core:runtime:test` — passed.

## Follow-up review fixes

- `ToolProvider.execute` now accepts only the catalog-issued `ValidatedToolCall`; its constructor is private, so raw model calls cannot bypass catalog/scope validation.
- Removed the internal issuance factory; no public constructor or factory can mint a validated call outside `ToolCatalog`.
- Replaced the mintable top-level class with a sealed `ValidatedToolCall` interface and private catalog-issued implementation; raw or externally minted calls cannot satisfy `ToolProvider.execute`.
- Bound issued calls to their originating catalog and scope identity; `ToolCatalog.execute` rejects cross-catalog and cross-scope reuse.
- Binding uses the exact immutable `ScopeSnapshot` object identity, so same-ID snapshots with different resources are rejected. `ValidatedToolCall` remains sealed, preventing raw provider arguments.
- Replaced enum-name persistence with explicit versioned wire tags (`v1:final`, `v1:tool_call`, `v1:escalate`, `v1:cancelled`, `v1:turn_limit`, `v1:failed`) and round-trip coverage for every terminal state.
- Validation and run-result failures use the existing model `ToolError` taxonomy. `CANCELLED` and `FAILED` were added to that normalized enum.
- `ModelProvider.respond` is abstract. Legacy planning is explicit through `LegacyModelProvider` and `LegacyModelProviderAdapter`; current one-shot runtime users were migrated to the named legacy interface.
- Added `AgentRunStateCodec` and round-trip tests for the durable state encoding.

## Concerns

The contract brief does not prescribe concrete field types for source, confirmation, scope, or skill metadata. The implementation uses stable strings/nullable resource IDs and explicit enums where safety behavior is required.
