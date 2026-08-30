# Stage 7 UI Capabilities Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or `superpowers:executing-plans` to implement this plan task-by-task.

**Goal:** Implement and verify the Stage 7 device capability groups, including UI, media, radios, and environment/I/O access.

**Architecture:** Add Android capability modules implementing the existing `AgentCapability` contract. Framework permissions and user grants remain explicit; core routing only sees typed tools and structured errors.

**Tech Stack:** Kotlin, Android APIs, Jetpack Compose-independent capability modules, MediaProjection, AccessibilityService, PackageManager, coroutines, Android instrumentation.

**Spec:** `docs/superpowers/specs/2026-08-29-stage-7-ui-capabilities-design.md`

## Global Constraints

- Keep Android APIs out of core modules.
- Return `PERMISSION_REQUIRED`, `UNSUPPORTED`, and `SECURE_WINDOW` truthfully.
- Do not grant accessibility or screen capture permissions automatically.
- Every capability must be scope-routed and covered by fake-adapter and connected tests.

### Task 1: App inspection capability

**Files:** Create `capabilities/apps/`; modify `settings.gradle.kts`, app wiring; test module unit and connected tests.

- [ ] Define `apps.list` and `apps.launch` typed tools and fake adapter tests.
- [ ] Implement PackageManager-backed inspection with package label, package name, enabled state, and launch intent only.
- [ ] Return `NOT_FOUND` for unknown packages and `APP_NOT_RUNNING`/structured failure for launch errors.
- [ ] Register handlers in application wiring and add connected package-list coverage.
- [ ] Commit `feat: add app inspection capability`.

### Task 2: Accessibility capability

**Files:** Create `capabilities/accessibility/`; modify manifest and app wiring; test unit and connected status.

- [ ] Define status and explicitly addressed inspect/action tool contracts.
- [ ] Implement an `AccessibilityService` adapter that reports disabled state as `PERMISSION_REQUIRED` and never self-enables.
- [ ] Add connected verification of disabled/enabled status and fake tests for unavailable service/action rejection.
- [ ] Commit `feat: add accessibility capability`.

### Task 3: Screen capture capability

**Files:** Create `capabilities/screen/`; modify manifest/app wiring and user-mediated MediaProjection launcher; test fake and connected outcomes.

- [ ] Define capture request/result contract and `SECURE_WINDOW` mapping.
- [ ] Implement MediaProjection-backed capture with explicit grant state and bounded image output.
- [ ] Add tests for missing grant, secure-window failure, and successful capture fixture.
- [ ] Commit `feat: add screen capture capability`.

### Task 4: Integration verification

- [ ] Run `git diff --check`.
- [ ] Run module unit tests and `./gradlew :app:lintDebug`.
- [ ] Run connected tests on SM-F711U1; record unsupported/permission-required outcomes without bypassing Android.
- [ ] Update Stage 7 device checklist and push `master`.

### Stage 7 completion record

The UI, media, radio, and environment capability groups are implemented and pushed on `master`. JVM tests, focused lint/compilation, and connected checks have passed on SM-F711U1. See [the device checklist](../../device-test/stage-7-capabilities.md) for manual permission-gated checks.
