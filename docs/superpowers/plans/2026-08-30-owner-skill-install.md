# Owner Skill Discovery and Installation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let owners discover, download, validate, install, list, and remove declarative skills from HTTPS URLs.

**Architecture:** Add a bounded HTTPS skill archive downloader and owner-gated management service over the existing validator/installer. Expose management through model tools and inject installed skill IDs into owner context after activation.

**Tech Stack:** Kotlin, coroutines, JUnit, existing `core/skills` validator/installer, Android private files, Room-backed durable state where needed.

**Spec:** `docs/superpowers/specs/2026-08-30-owner-skill-install-design.md`

## Global Constraints

- HTTPS URLs only; reject redirects, private/unbounded responses, unsafe archive paths, and executable payloads.
- Installation is owner-only and must pass validation plus dry-run before activation.
- Secrets and downloaded content must not be logged.

### Task 1: Skill download and owner management boundary

**Files:**
- Create: `core/skills/src/main/kotlin/com/fsaint/androidagent/skills/SkillDownloadService.kt`
- Test: `core/skills/src/test/kotlin/com/fsaint/androidagent/skills/SkillDownloadServiceTest.kt`

- [ ] Write failing tests for HTTPS-only bounded downloads, redirect rejection, hash verification, owner-only install, and list/remove behavior.
- [ ] Implement transport and service interfaces with deterministic fakes for tests.
- [ ] Run focused core/skills tests.
- [ ] Commit `feat: add owner skill download service`.

### Task 2: Android private storage and model tools

**Files:**
- Create: `app/src/main/kotlin/com/fsaint/androidagent/SkillDownloadTransport.kt`
- Modify: `app/src/main/kotlin/com/fsaint/androidagent/DarkLordApplication.kt`
- Modify: `core/policy/src/main/kotlin/com/fsaint/androidagent/policy/AuthorizationPolicy.kt`
- Test: app/runtime tests for owner-only tool routing and installed-skill context.

- [ ] Add private-files storage implementing the skill store with atomic replacement and removal.
- [ ] Register owner-gated `skills.download`, `skills.install`, `skills.list`, and `skills.remove` handlers.
- [ ] Add installed skill IDs to the owner model context catalog without exposing them to unknown principals.
- [ ] Run focused tests and compile the debug app.
- [ ] Commit `feat: expose owner skill management tools`.

### Task 3: Device verification and documentation

**Files:**
- Modify: `README.md`
- Modify: `docs/getting-started.md` (if present)

- [ ] Document the owner workflow, URL/hash format, validation failures, and rollback/removal behavior.
- [ ] Install the debug APK on the connected device.
- [ ] Verify owner list/download/install/remove and unknown-user denial with a safe test package.
- [ ] Run the full relevant test suite and commit `docs: document owner skill management`.
