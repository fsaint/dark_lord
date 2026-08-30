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

## Concerns

The contract brief does not prescribe concrete field types for source, confirmation, scope, or skill metadata. The implementation uses stable strings/nullable resource IDs and explicit enums where safety behavior is required. The existing `ModelProvider.plan` method remains available for current integrations; future harness code should use `respond`.
