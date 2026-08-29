# Stage 6 Communications Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver scoped SMS, dialer, notification, and owner-administration capabilities for Dark Lord on Android 15.

**Architecture:** Android-facing capability modules normalize framework callbacks into `AgentEvent`s without depending on the planner. The app composition root persists each event, resolves its principal from Room-backed administration data, then dispatches it through the existing scoped runtime. System defaults and notification access remain explicit user grants.

**Tech Stack:** Kotlin, Android RoleManager, Telephony/SMS APIs, Telecom `InCallService`, `NotificationListenerService`, Room, Coroutines/Flow, Jetpack Compose, JUnit 5, AndroidX instrumentation.

**Spec:** `docs/superpowers/specs/2026-08-29-stage-6-communications-design.md`

## Global Constraints

- Keep `minSdk = 31`, `targetSdk = 35`, `compileSdk = 35`, Java/Kotlin 17.
- Do not grant `ROLE_SMS`, `ROLE_DIALER`, notification access, or runtime permissions automatically.
- Normalize an inbound source before principal/scope resolution and persist its event before planner dispatch.
- Do not record or claim to capture PSTN audio.
- Report SMS submission and carrier delivery with their actual verification states.
- Maintain Android-free `core:model`, `core:policy`, and `core:runtime` interfaces.
- Emergency dialing delegates through `TelecomManager.placeCall`; Android selects its preloaded emergency dialer.

---

### Task 1: Durable principal directory and communications dispatcher

**Files:**
- Modify: `core/data/src/main/kotlin/com/fsaint/androidagent/data/AgentDatabase.kt`
- Modify: `core/data/src/main/kotlin/com/fsaint/androidagent/data/Repositories.kt`
- Modify: `core/policy/src/main/kotlin/com/fsaint/androidagent/policy/AuthorizationPolicy.kt`
- Create: `app/src/main/kotlin/com/fsaint/androidagent/communications/CommunicationsDispatcher.kt`
- Test: `core/data/src/test/kotlin/com/fsaint/androidagent/data/PrincipalRepositoryTest.kt`
- Test: `app/src/test/kotlin/com/fsaint/androidagent/communications/CommunicationsDispatcherTest.kt`

**Consumes:** `AgentEvent`, `Principal`, `ScopeRegistry.sessionFor`, `AgentRuntime.process`.

**Produces:**

```kotlin
interface PrincipalDirectory {
    suspend fun owner(): Principal?
    suspend fun lookup(e164: String): Principal?
    suspend fun list(): List<Principal>
    suspend fun upsert(principal: Principal)
    suspend fun removeKnown(e164: String): Boolean
}
class CommunicationsDispatcher(
    private val principals: PrincipalDirectory,
    private val scopes: ScopeRegistry,
    private val runtime: AgentRuntime,
) { suspend fun dispatch(event: AgentEvent, channel: String) }
```

- [ ] **Step 1: Write failing persistence and dispatch tests**

```kotlin
@Test fun knownPrincipalSurvivesRepositoryReopen() = runTest {
    repository.upsert(Principal("alice", "+14155550100", PrincipalRole.KNOWN))
    assertThat(reopened.lookup("+14155550100")).isEqualTo(Principal("alice", "+14155550100", PrincipalRole.KNOWN))
}

@Test fun unknownSmsDispatchesAnUnknownScopedSession() = runTest {
    dispatcher.dispatch(AgentEvent("1", "sms.received", "+14155550199", 1, mapOf("sender" to "+14155550199")), "SMS")
    assertThat(fakeRuntime.sessions.single().role).isEqualTo(PrincipalRole.UNKNOWN)
}
```

- [ ] **Step 2: Run the new tests to verify they fail**

Run: `./gradlew :core:data:testDebugUnitTest :app:testDebugUnitTest --tests '*PrincipalRepositoryTest' --tests '*CommunicationsDispatcherTest'`

Expected: FAIL because `PrincipalDirectory` and `CommunicationsDispatcher` do not exist.

- [ ] **Step 3: Add Room queries, repository, and dispatch implementation**

```kotlin
@Query("SELECT * FROM principals WHERE e164 = :e164 LIMIT 1") suspend fun principalByE164(e164: String): PrincipalEntity?
@Query("SELECT * FROM principals ORDER BY role, displayName") suspend fun principals(): List<PrincipalEntity>
@Query("DELETE FROM principals WHERE e164 = :e164 AND role = 'KNOWN'") suspend fun deleteKnown(e164: String): Int
```

Add `PrincipalRepository` implementing `PrincipalDirectory`, normalize before lookup through `PrincipalRegistry`, and create synthetic `Principal("unknown:$e164", e164, UNKNOWN)` when no stored record exists. Increase the Room schema version and provide a migration that preserves all existing tables while adding only required indexes. `CommunicationsDispatcher` must call `runtime.process(scopes.sessionFor(principal, channel), event)`; `AgentRuntime` remains responsible for durable enqueue before planning.

- [ ] **Step 4: Run the focused tests to verify they pass**

Run: `./gradlew :core:data:testDebugUnitTest :app:testDebugUnitTest --tests '*PrincipalRepositoryTest' --tests '*CommunicationsDispatcherTest'`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/data core/policy app/src/main/kotlin/com/fsaint/androidagent/communications app/src/test
git commit -m "feat: add durable communications principal routing"
```

### Task 2: Default-SMS capability and verified transport events

**Files:**
- Create: `capabilities/sms/build.gradle.kts`
- Create: `capabilities/sms/src/main/kotlin/com/fsaint/androidagent/capabilities/sms/SmsCapability.kt`
- Create: `capabilities/sms/src/main/kotlin/com/fsaint/androidagent/capabilities/sms/SmsBroadcastReceiver.kt`
- Create: `capabilities/sms/src/main/kotlin/com/fsaint/androidagent/capabilities/sms/SmsReplySender.kt`
- Create: `capabilities/sms/src/androidTest/kotlin/com/fsaint/androidagent/capabilities/sms/SmsEventTest.kt`
- Modify: `settings.gradle.kts`
- Modify: `app/build.gradle.kts`

**Consumes:** `AgentCapability`, `AgentEvent`, `ToolResult`, `ToolError`, `VerificationState`.

**Produces:**

```kotlin
interface SmsEventSink { fun publish(event: AgentEvent) }
class SmsBroadcastReceiver : BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) }
class SmsReplySender(context: Context, private val sink: SmsEventSink) {
    fun send(destination: String, body: String, subscriptionId: Int? = null): ToolResult<String>
}
```

- [ ] **Step 1: Write failing SMS receiver and delivery-state tests**

```kotlin
@Test fun smsDeliverCreatesOneEventForEachPdu() {
    receiver.onReceive(context, smsDeliverIntent(pdus = listOf(pdu("+14155550100", "battery"))))
    assertThat(sink.events.single()).isEqualTo(AgentEvent("sms:...", "sms.received", "+14155550100", ANY, mapOf("sender" to "+14155550100", "body" to "battery")))
}

@Test fun submittedSmsIsUnverifiedUntilCarrierDelivery() {
    assertThat(sender.send("+14155550100", "72%").verification).isEqualTo(VerificationState.UNVERIFIED)
}
```

- [ ] **Step 2: Run the instrumentation tests to verify they fail**

Run: `./gradlew :capabilities:sms:connectedDebugAndroidTest`

Expected: FAIL because the SMS module and receiver do not exist.

- [ ] **Step 3: Implement receiver, sender, and capability**

Decode `Telephony.Sms.Intents.getMessagesFromIntent(intent)` only for `SMS_DELIVER_ACTION` and `SMS_RECEIVED_ACTION`, publish event IDs formed from subscription/message timestamp/address, and retain `sender`, `body`, and `subscriptionId` payload fields. `SmsReplySender` must return `PERMISSION_REQUIRED` unless `RoleManager.isRoleHeld(ROLE_SMS)` and `SEND_SMS` is granted; use `SmsManager.createForSubscriptionId` when supplied. Publish `sms.sent` and `sms.delivered` events from explicit non-exported result receivers; only `sms.delivered` has `VERIFIED` delivery evidence.

- [ ] **Step 4: Run the SMS tests to verify they pass**

Run: `./gradlew :capabilities:sms:connectedDebugAndroidTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts app/build.gradle.kts capabilities/sms
git commit -m "feat: add default SMS capability"
```

### Task 3: Default dialer capability and safe call controls

**Files:**
- Create: `capabilities/telephony/build.gradle.kts`
- Create: `capabilities/telephony/src/main/kotlin/com/fsaint/androidagent/capabilities/telephony/AgentInCallService.kt`
- Create: `capabilities/telephony/src/main/kotlin/com/fsaint/androidagent/capabilities/telephony/CallController.kt`
- Create: `capabilities/telephony/src/main/kotlin/com/fsaint/androidagent/capabilities/telephony/CallEventPublisher.kt`
- Create: `capabilities/telephony/src/androidTest/kotlin/com/fsaint/androidagent/capabilities/telephony/CallStateTest.kt`
- Modify: `settings.gradle.kts`
- Modify: `app/build.gradle.kts`

**Consumes:** `AgentEvent`, `ToolResult`, `Call.Details` capabilities.

**Produces:**

```kotlin
interface CallHandle { val id: String; val state: Int; val capabilities: Int
    fun answer(); fun reject(); fun disconnect(); fun hold(); fun unhold(); fun setMuted(muted: Boolean) }
class CallController { fun answer(call: CallHandle): ToolResult<Unit>; fun hold(call: CallHandle): ToolResult<Unit> }
```

- [ ] **Step 1: Write failing call-control and event tests**

```kotlin
@Test fun unsupportedHoldIsReportedWithoutCallingTelecom() {
    assertThat(controller.hold(fakeCall(capabilities = 0)).error).isEqualTo(ToolError.UNSUPPORTED)
    assertThat(fakeCall.holdCalls).isZero()
}

@Test fun incomingCallPublishesRingingState() {
    service.onCallAdded(fakeCall(state = Call.STATE_RINGING))
    assertThat(sink.events.single().type).isEqualTo("call.state")
}
```

- [ ] **Step 2: Run the instrumentation tests to verify they fail**

Run: `./gradlew :capabilities:telephony:connectedDebugAndroidTest`

Expected: FAIL because the telephony module does not exist.

- [ ] **Step 3: Implement the non-null InCallService and control boundary**

`AgentInCallService.onCallAdded` must retain a call handle, add a callback, publish initial/changed state, and invoke an app-supplied UI launcher. `CallController` checks `Call.Details.CAPABILITY_HOLD` before hold/unhold and exposes only `answer`, `reject`, `disconnect`, `mute`, and supported hold actions. It must return `UNSUPPORTED` for unavailable operations and never include audio-capture code.

- [ ] **Step 4: Run the telephony tests to verify they pass**

Run: `./gradlew :capabilities:telephony:connectedDebugAndroidTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts app/build.gradle.kts capabilities/telephony
git commit -m "feat: add dialer call-state capability"
```

### Task 4: Notification listener capability

**Files:**
- Create: `capabilities/notifications/build.gradle.kts`
- Create: `capabilities/notifications/src/main/kotlin/com/fsaint/androidagent/capabilities/notifications/AgentNotificationListenerService.kt`
- Create: `capabilities/notifications/src/androidTest/kotlin/com/fsaint/androidagent/capabilities/notifications/NotificationEventTest.kt`
- Modify: `settings.gradle.kts`
- Modify: `app/build.gradle.kts`

**Consumes:** `StatusBarNotification`, `AgentEvent`.

**Produces:**

```kotlin
class AgentNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected()
    override fun onNotificationPosted(sbn: StatusBarNotification)
}
```

- [ ] **Step 1: Write a failing notification event test**

```kotlin
@Test fun listenerPublishesOnlyAfterConnection() {
    service.onNotificationPosted(notification("pkg", "title", "body"))
    service.onListenerConnected()
    service.onNotificationPosted(notification("pkg", "title", "body"))
    assertThat(sink.events).hasSize(1)
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :capabilities:notifications:connectedDebugAndroidTest`

Expected: FAIL because the notifications module does not exist.

- [ ] **Step 3: Implement the connection-gated listener**

Maintain a private `connected` flag set by `onListenerConnected`. On a posted notification, publish a `notification.posted` event with notification key, package, category, post time, title, and text extracted from `Notification.EXTRA_TITLE` and `Notification.EXTRA_TEXT`; convert absent text to an empty string. Do not cancel, alter, or expose notifications directly from this service.

- [ ] **Step 4: Run the notification test to verify it passes**

Run: `./gradlew :capabilities:notifications:connectedDebugAndroidTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts app/build.gradle.kts capabilities/notifications
git commit -m "feat: add notification intake capability"
```

### Task 5: App role wiring, Compose administration, and owner commands

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/kotlin/com/fsaint/androidagent/MainActivity.kt`
- Modify: `app/src/main/kotlin/com/fsaint/androidagent/DarkLordApplication.kt`
- Create: `app/src/main/kotlin/com/fsaint/androidagent/communications/OwnerSmsCommandHandler.kt`
- Create: `app/src/main/kotlin/com/fsaint/androidagent/ui/PrincipalSettingsScreen.kt`
- Create: `app/src/main/kotlin/com/fsaint/androidagent/ui/DialerActivity.kt`
- Create: `app/src/main/kotlin/com/fsaint/androidagent/ui/CallScreenActivity.kt`
- Test: `app/src/test/kotlin/com/fsaint/androidagent/communications/OwnerSmsCommandHandlerTest.kt`
- Test: `app/src/androidTest/kotlin/com/fsaint/androidagent/RoleQualificationTest.kt`

**Consumes:** all capability event sinks, `PrincipalDirectory`, `CommunicationsDispatcher`, `RoleManager`.

**Produces:**

```kotlin
class OwnerSmsCommandHandler(private val principals: PrincipalDirectory) {
    suspend fun handle(sender: Principal, body: String): ToolResult<String>
}
fun MainActivity.requestCommunicationsRoles()
fun MainActivity.openNotificationListenerSettings()
```

- [ ] **Step 1: Write failing owner-command and role-qualification tests**

```kotlin
@Test fun knownAddIsAcceptedOnlyFromOwner() = runTest {
    assertThat(handler.handle(owner, "KNOWN ADD +14155550100").success).isTrue()
    assertThat(handler.handle(unknown, "KNOWN ADD +14155550101").error).isEqualTo(ToolError.SCOPE_DENIED)
}

@Test fun packageQualifiesForDialerAndSmsRoles() {
    assertThat(roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)).isTrue()
    assertThat(packageManager.queryIntentActivities(Intent(Intent.ACTION_DIAL, Uri.parse("tel:123")), 0)).isNotEmpty()
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest --tests '*OwnerSmsCommandHandlerTest' --tests '*RoleQualificationTest'`

Expected: FAIL because command handling and qualified manifest components do not exist.

- [ ] **Step 3: Wire manifest, role prompts, screens, and commands**

Add the SMS default-handler receiver/activity/service declarations, `ACTION_DIAL` activity, and `InCallService` with `BIND_INCALL_SERVICE` and UI metadata. Add `AgentNotificationListenerService` with `BIND_NOTIFICATION_LISTENER_SERVICE`. Request `ROLE_SMS` and `ROLE_DIALER` through separate `ActivityResultContracts.StartActivityForResult` launchers, and open `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS` only from a settings button. Request `READ_SMS`, `RECEIVE_SMS`, `SEND_SMS`, `READ_PHONE_STATE`, `READ_CALL_LOG`, `CALL_PHONE`, and notification permission with clear role-dependent status.

`OwnerSmsCommandHandler` accepts exact, trimmed, case-insensitive `STATUS`, `KNOWN ADD <E.164>`, and `KNOWN REMOVE <E.164>` forms only when `sender.role == OWNER`. It returns `SCOPE_DENIED` for every non-owner administrative request. Compose settings displays each principal and role state, supports add/remove known numbers, and does not store malformed numbers.

- [ ] **Step 4: Run the app tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest --tests '*OwnerSmsCommandHandlerTest' --tests '*RoleQualificationTest'`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app
git commit -m "feat: wire communications roles and owner administration"
```

### Task 6: End-to-end verification and device acceptance

**Files:**
- Modify: `README.md`
- Create: `docs/device-test/stage-6-communications.md`

- [ ] **Step 1: Add the physical device checklist**

Document the exact system prompts for default SMS, default dialer, notification access, SMS/call permissions, plus restoration steps to Google Messages and Samsung Dialer if the tester stops. Include these acceptance checks: a real inbound owner SMS creates an OWNER session; an unknown SMS receives no owner data; owner `KNOWN ADD` changes the local list; an incoming and outgoing call show state/UI; a posted notification generates an event; a known-person escalation persists and resumes after the owner reply.

- [ ] **Step 2: Run full static, unit, and connected-device verification**

Run: `./gradlew test lint connectedCheck`

Expected: PASS; any carrier-dependent tests must use `Assume` and report as skipped rather than simulate carrier verification.

- [ ] **Step 3: Install and perform the physical Flip3 test**

Run: `adb install -r app/build/outputs/apk/debug/app-debug.apk`

On the Flip3, accept the three Android grants. Send an SMS from a second device, place/receive a non-emergency call, post a test notification, then inspect persisted/audited events with the app debug screen or `adb logcat` scoped to Dark Lord. Record each observed outcome in `docs/device-test/stage-6-communications.md`.

- [ ] **Step 4: Commit and push**

```bash
git add README.md docs/device-test
git commit -m "docs: add Stage 6 device validation"
git push origin master
```

## Plan self-review

- Spec coverage: Tasks 1–5 cover all required capability boundaries, principal administration, Android role requirements, safety behavior, and structured failures. Task 6 covers the required automated and physical acceptance evidence.
- Placeholder scan: no deferred implementation markers or unspecified error-handling steps remain.
- Type consistency: every capability emits `AgentEvent`; `CommunicationsDispatcher` alone creates `ScopedAgentSession` and invokes `AgentRuntime`; application services depend on app-owned sinks rather than core runtime internals.
