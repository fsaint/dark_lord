# Stage 7 Capability Device Checklist

Device: Samsung Galaxy Z Flip3 (SM-F711U1), Android 15.

Automated verification covers JVM tests, module/app lint, app compilation, and connected status tests for all Stage 7 capability modules. The connected checks intentionally preserve Android permission boundaries and may report `PERMISSION_REQUIRED`, `UNSUPPORTED`, or disabled state.

For manual capture or recording checks, install the debug APK, grant Camera and Microphone when prompted, and invoke the corresponding capability through the agent. Screen capture and Accessibility require their normal user-mediated system consent flows; they are never auto-enabled.

Radio checks do not enable Bluetooth/Wi-Fi, pair devices, scan, or change networks. Location, NFC, USB, contacts, and app-private file checks are read-only and bounded.
