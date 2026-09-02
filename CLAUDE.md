# Dark Lord project guidance

For Android device validation, use the `dark-lord-device-testing` skill at `.agents/skills/dark-lord-device-testing/SKILL.md`.

The skill documents the 66-request local chat API suite, safe defaults, side-effect test opt-in, device setup, reports, and failure interpretation. Before claiming a device test passes, verify the API health endpoint, save the JSON report, and check `adb logcat` for crashes or transport errors.
