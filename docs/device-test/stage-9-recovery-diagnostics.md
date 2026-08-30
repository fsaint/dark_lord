# Stage 9 Recovery and Diagnostics Device Checklist

Device: Samsung Galaxy Z Flip3 (SM-F711U1), Android 15.

Automated verification covers the Android-free scheduler and boot coordinator, WorkManager/boot receiver wiring, explicit Device Admin metadata, diagnostics redaction/export, debug fixture validation, and OEM posture reporting.

Manual recovery check:

1. Follow the [Device Owner provisioning guide](../device-provisioning/galaxy-z-flip3-reset-and-device-owner.md) on a reset-sensitive test device.
2. Confirm `adb shell dpm list-owners` reports Dark Lord as the device owner.
3. Reboot the phone and confirm the unique restore worker is enqueued once, pending work is retained, and the foreground runtime resumes.
4. Open Diagnostics from the local app and confirm inspectors are visible while secrets, token values, message bodies, and private file contents remain redacted.

Do not use remote or unknown principals to invoke debug actions. Event injection is limited to typed local fixtures and must still pass normal authorization and audit paths.
