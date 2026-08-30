# Stage 7 UI Capabilities Design

## Goal

Add the first Stage 7 capability group: app inspection, accessibility inspection/action boundaries, and screen capture. Each adapter remains Android-specific and exposes typed, scope-checked tools through the existing `AgentCapability` contract.

## Boundaries

- `apps.inspect` reports installed/launchable applications without exposing private app data.
- `accessibility.inspect` reports whether the service is enabled and returns a structured permission-required result when unavailable; actions are limited to explicitly addressed UI targets.
- `screen.capture` requests/uses MediaProjection through a user-mediated grant and returns `SECURE_WINDOW` when capture is blocked by Android.
- Core runtime and policy remain Android-free; capability adapters own framework calls and permission checks.
- No automatic accessibility, screen-capture, or overlay grants are added.

## Testing

Each module gets fake-adapter JVM tests for success, permission-required, unsupported, and secure-window outcomes, plus connected tests that inspect the real Flip3 capability status without fabricating grants.
