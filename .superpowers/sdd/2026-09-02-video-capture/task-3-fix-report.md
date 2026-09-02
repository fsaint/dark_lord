# Task 3 P1 fix report

## Fixed findings

1. `jobs.cancel` now reads the job record after `cancelAndJoin`, preserving the MP4 artifact written by video finalization. The cancellation lifecycle test asserts the returned `CANCELLED` payload retains the opaque artifact metadata.
2. Video jobs wait for an in-process acknowledgement from `AgentRuntimeService` before camera access. The service sends that acknowledgement only after `startForeground(..., CAMERA | MICROPHONE)` returns. The wait is bounded to five seconds; failure to start/promote the service fails the job rather than recording before the typed foreground service exists.
3. Media teardown is separated from normal runtime ownership. When runtime work is still active, media teardown restores the special-use runtime foreground notification instead of removing it. `MediaForegroundLifecycle` has coverage for the preservation decision.
4. Direct `camera.startVideo`, `camera.stopVideo`, `microphone.start`, `microphone.stop`, and `microphone.record` are overridden with lease-aware handlers. They share the process-wide media lease with background jobs and return `DEVICE_BUSY` while another recording owner holds it. Idle stop calls still delegate to the underlying capability.

## Verification

- `./gradlew :app:testDebugUnitTest --tests com.fsaint.androidagent.BackgroundJobManagerTest`
- `./gradlew :app:compileDebugAndroidTestKotlin`
- `git diff --check`

## Typed FGS acknowledgement scope

The acknowledgement is safely implementable in the current single-process architecture because the `AndroidMediaJobForeground` request map and `AgentRuntimeService` run in the same application process. It is not a synthetic delay: recording remains suspended until the service has executed the typed `startForeground` call. It does not substitute for device-level Android 14+ validation of permissions and foreground-service policy; that remains an instrumentation/device acceptance concern.
