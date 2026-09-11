# Natural-language MCP access acceptance

Date: 2026-09-10. Device: Samsung Flip3, Android 15/API 35.
Debug package: `com.fsaint.androidagent`, version 0.1.0.
Final installation timestamp on device: 11:31:42 local time.

## Change and verification

- Current authorized tool names and purposes are presented after saved history.
- The model is instructed to discover accounts through relevant read-only tools before denying access, without treating capability questions as permission to send or modify data.
- Common unsupported access denials get one corrective pass. Only app-backed inventory is automatic. On that pass, historical assistant denial prose is excluded from model context; user constraints, recorded tool outcomes, and stored chat are preserved.
- Regression tests cover bounded correction, preservation of user intent and execution evidence, real permission failures without retries, and ordinary conversations without automatic actions.
- `./gradlew test :app:lintDebug :app:assembleDebug :app:installDebug --no-daemon --max-workers=2` succeeded. 575 tests, zero failures/errors/skips. Lint: zero errors, 63 warnings.
- Python MCP evidence-validator suite: 11 tests passed. Independent review found no substantive issues.

## Live evidence

Prompt in both checks: **can you read my email**. No tool names or account identifiers were supplied.

| Surface | Observed execution | Result |
| --- | --- | --- |
| Stateless phone chat API | `gmail_list_accounts`, `gmail_list_messages` | Both successful and VERIFIED; FINAL_RESPONSE |
| Existing Outside chat, first attempt | `mcp_inventory` only | CAPABILITY_UNVERIFIED; exposed persistent-history anchoring and led to the history correction |
| Existing Outside chat, final installed build | `mcp_inventory`, `gmail_list_accounts`, `gmail_list_messages` | All successful and VERIFIED; completed email response with no unsupported access denial |

Final Outside request ID: `7ce92296-e360-4809-84da-9cb1801e6bda`.
Metadata-only `DarkLordTools` logs recorded inventory at 11:32:26, account discovery at 11:32:30, and message listing at 11:32:34. No send, delete, or other write tool executed.

The original API smoke report failed its single-tool assertion because the natural request also listed messages. The unchanged saved response was subsequently validated with account discovery required and message listing explicitly allowed. Unexpected tools, failures, and unverified results still fail validation. This allowlist validates evidence after execution; it does not enforce execution permissions.

The Outside check used typed input through `ChatTurnCoordinator`, preserving the existing history containing earlier denials. It validates the shared conversation path but is not a physical speech-recognition test. A spoken retry remains a manual acceptance step.

The denial guard is a bounded English-language heuristic, not a semantic verifier of all model claims or a guarantee of future model behavior. Private account details and message contents are intentionally excluded from this report. Temporary USB forwarding was removed, and the local chat API was restored to disabled.
