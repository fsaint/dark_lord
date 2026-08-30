# Stage 10 Release and Acceptance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Encode the prototype acceptance scenarios, produce a reproducibly signed sideloadable APK, and publish the final Flip3 test/release evidence.

**Architecture:** Keep acceptance traceability in a versioned checklist that distinguishes automated, manual, unsupported, and blocked outcomes. Add a development-only instrumentation smoke suite for manifest, diagnostics, recovery, scope, and permission boundaries. Configure the prototype release variant to use the local debug signing key (never commit private keys) and document replacement with a production keystore.

**Tech Stack:** Kotlin, Android instrumentation, Gradle Android application plugin, Jetpack Compose UI tests, ADB sideloading.

**Spec:** `SPEC.md` sections 64–67 and `docs/superpowers/plans/2026-08-29-android-agent-prototype.md` Task 10.

## Global Constraints

- Do not claim carrier, Tailscale, OAuth, or second-endpoint behavior from local unit tests alone.
- Record unsupported hardware and permission-refused outcomes honestly; never bypass Android prompts with `pm grant` or role commands.
- Release artifacts must be reproducible from source and signed without committing private key material.
- Acceptance evidence must contain timestamps, build identity, device identity, and audit/event references where available.

### Task 1: Build traceable acceptance checklist

**Files:**
- Create: `docs/acceptance/flip3-prototype-checklist.md`
- Create: `docs/release/sideloading.md`

- [ ] Map scenarios 01–28 to automated tests, manual device checks, or explicit unsupported/deployment-specific checks.
- [ ] Include Assistant open/closed posture, cover touch, voice request/response, reboot recovery, permission refusal, scope denial, and MCP OAuth failure.
- [ ] Define an evidence record format with timestamp, commit, APK hash, device serial/model, disposition, and audit identifiers.
- [ ] Document safe ADB install/sideload and restoration steps without private credentials.
- [ ] Commit `docs: add prototype acceptance and sideloading checklists`.

### Task 2: Add release signing and artifact identity

**Files:**
- Modify: `app/build.gradle.kts`
- Test: `app/src/test/kotlin/com/fsaint/androidagent/ReleaseArtifactConfigTest.kt`

- [ ] Test that the release variant is signed/configured and has a non-debuggable, versioned artifact identity.
- [ ] Configure prototype release signing from the local debug signing config only when no production keystore is supplied; support `DARK_LORD_RELEASE_STORE_FILE` environment configuration without storing secrets.
- [ ] Add an artifact checksum task/output and document the expected APK path and SHA-256 recording.
- [ ] Commit `build: configure signed prototype release artifact`.

### Task 3: Encode acceptance smoke tests

**Files:**
- Create: `app/src/androidTest/kotlin/com/fsaint/androidagent/PrototypeAcceptanceTest.kt`
- Modify: existing app test helpers only as needed

- [ ] Add instrumentation assertions for manifest-facing components, diagnostics navigation, boot/device-admin registration, scope-denied behavior, and permission-required reporting.
- [ ] Keep carrier, external MCP, Tailscale, and physical cover/voice checks in the manual checklist rather than fabricating them in tests.
- [ ] Run the focused connected smoke suite on SM-F711U1 and commit `test: add prototype acceptance smoke suite`.

### Task 4: Run release gate and publish evidence

- [ ] Run `./gradlew test lintDebug connectedCheck assembleRelease` (or record the exact constrained equivalent if an environmental resource limit prevents the full command).
- [ ] Verify the signed APK exists under `app/build/outputs/apk/release/`, record SHA-256, and install it on SM-F711U1.
- [ ] Update README status and add a dated acceptance evidence entry; run `git diff --check`.
- [ ] Commit `docs: record validated Flip3 prototype release` and push `master`.

