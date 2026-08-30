# First Owner Setup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Provide a one-time, audited on-device flow that provisions the first E.164 number as the sole `OWNER` principal.

**Architecture:** Add a narrow `provisionInitialOwner` operation to the principal-directory boundary. The app exposes that operation only when no owner exists, requires explicit confirmation, and persists the owner plus an audit record before hiding the bootstrap UI. Existing owner-gated SMS administration remains unchanged.

**Tech Stack:** Kotlin, Jetpack Compose, Room, coroutines, existing `PrincipalDirectory`/`AuditStore` abstractions, Android instrumentation and JVM tests.

**Spec:** In-chat approved first-owner setup design from 2026-08-29.

## Global Constraints

- Only the first owner may be provisioned through this flow; replacement and deletion are not exposed.
- Input must be a valid E.164 number (`+` followed by 1–15 digits, first digit non-zero).
- Provisioning must be durable and audited before success is shown.
- Existing `KNOWN` administration remains owner-gated.
- No `adb` grants, database edits, or automatic owner inference are permitted.

---

### Task 1: Durable first-owner repository operation

**Files:**
- Modify: `core/policy/src/main/kotlin/com/fsaint/androidagent/policy/AuthorizationPolicy.kt`
- Modify: `core/data/src/main/kotlin/com/fsaint/androidagent/data/Repositories.kt`
- Modify: `core/data/src/main/kotlin/com/fsaint/androidagent/data/AgentDatabase.kt` only if schema support is required
- Test: `core/data/src/test/kotlin/com/fsaint/androidagent/data/PrincipalRepositoryTest.kt`

**Interfaces:**
- Produces `suspend fun provisionInitialOwner(e164: String): Principal` on the directory boundary.
- Returns a persisted `PrincipalRole.OWNER` when no owner exists; rejects invalid input, an existing owner, or an E.164 already assigned to another principal.

- [ ] **Step 1: Write failing repository tests** for valid first-owner creation, duplicate-owner rejection, invalid E.164 rejection, and restart-visible persistence.
- [ ] **Step 2: Run** `./gradlew :core:data:testDebugUnitTest --tests '*PrincipalRepositoryTest*'` and confirm the new tests fail because the operation is absent.
- [ ] **Step 3: Implement** the interface and Room-backed transaction using the existing unique E.164 constraints.
- [ ] **Step 4: Run** the focused repository tests and confirm all pass.
- [ ] **Step 5: Commit** with `feat: add durable initial owner provisioning`.

### Task 2: Audited owner provisioning service

**Files:**
- Create: `app/src/main/kotlin/com/fsaint/androidagent/communications/OwnerProvisioningService.kt`
- Modify: `app/src/main/kotlin/com/fsaint/androidagent/DarkLordApplication.kt`
- Test: `app/src/test/kotlin/com/fsaint/androidagent/communications/OwnerProvisioningServiceTest.kt`

**Interfaces:**
- `OwnerProvisioningService.provision(e164: String): Result<Principal>` validates the one-time precondition, calls the repository, and appends an `AuditRecord` with a redacted result and `ALLOW` authorization.
- The service is app-owned and reused by the UI; no UI code writes principals or audit records directly.

- [ ] **Step 1: Write failing tests** for successful audited provisioning, no-owner precondition failure, and audit-before-success ordering.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*OwnerProvisioningServiceTest*'` and confirm failure.
- [ ] **Step 3: Implement** the service using application-owned repository and audit dependencies.
- [ ] **Step 4: Run** the focused tests and confirm pass.
- [ ] **Step 5: Commit** with `feat: audit initial owner provisioning`.

### Task 3: One-time Compose setup UI

**Files:**
- Modify: `app/src/main/kotlin/com/fsaint/androidagent/ui/PrincipalSettingsScreen.kt`
- Modify: `app/src/main/kotlin/com/fsaint/androidagent/MainActivity.kt` or `DarkLordApplication.kt` for service wiring
- Test: `app/src/androidTest/kotlin/com/fsaint/androidagent/OwnerProvisioningUiTest.kt`

**Interfaces:**
- The screen receives `owner: Principal?` and `onProvisionOwner: suspend (String) -> Result<Principal>`.
- When `owner == null`, it renders a clearly labeled one-time setup card with E.164 input, explicit confirmation checkbox, submit button, and validation/error text.
- When `owner != null`, it renders owner status and no provisioning controls.

- [ ] **Step 1: Add failing UI tests** covering hidden setup after owner exists, disabled submit until confirmation, invalid-number error, and successful setup state.
- [ ] **Step 2: Run** the targeted instrumentation test and confirm failure.
- [ ] **Step 3: Implement** the card, state handling, and service callback without changing existing known-principal behavior.
- [ ] **Step 4: Run** the targeted UI test and app lint.
- [ ] **Step 5: Commit** with `feat: add one-time owner setup UI`.

### Task 4: Full verification and device validation

**Files:**
- Modify: `docs/device-test/stage-6-communications.md`
- Modify: `README.md` only if the communications status needs a link/update

- [ ] **Step 1: Run** `git diff --check`.
- [ ] **Step 2: Run** `./gradlew test lint connectedCheck` on the connected Flip3.
- [ ] **Step 3: Install the resulting APK and open Communications administration.** Verify the owner setup card is visible on a fresh data state or test fixture.
- [ ] **Step 4: Enter the tester’s E.164 number, confirm, and provision once.** Verify the owner status appears after refresh/restart and the setup card cannot be used again.
- [ ] **Step 5: Run owner `KNOWN ADD` and escalation `APPROVE`/`REJECT` checks from the provisioned owner phone.
- [ ] **Step 6: Document exact observations with redacted IDs and commit with `docs: document initial owner setup validation`.

