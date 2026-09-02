---
name: dark-lord-device-testing
description: Use when validating Dark Lord on an Android device through its local chat API, including hardware, browser, Python, artifact, MCP, and skill requests.
---

# Dark Lord Device Testing

Use the repository’s black-box API suite for repeatable device validation. The suite is intentionally owner-scoped and the local API has no authentication, so only run it on a dedicated test phone or a private Tailscale network.

## Preconditions

- Confirm the device is connected: `adb devices`.
- Build and install the current debug APK: `./gradlew :app:installDebug --no-daemon --max-workers=2`.
- Launch Dark Lord and enable **Local chat API** in the app. Verify it with:
  `curl http://dark-lord.curl-newton.ts.net:8765/health`.
- Confirm the owner, OpenAI key, and required Android permissions are configured.

## Run tests

From the repository root:

```bash
python3 tools/chat_api_test.py \
  --url http://dark-lord.curl-newton.ts.net:8765 \
  --timeout 90 \
  --report /tmp/dark-lord-chat-api.json
```

The suite contains 66 requests. By default it skips explicit side effects (recording audio, starting jobs, sending SMS, placing a call, and changing volume). Run those only with `--include-side-effects` and explicit owner approval. Run a focused case with `--case <id>`; repeat the option for multiple cases.

## Interpret results

Treat HTTP errors, timeouts, crashes, and `The agent did not produce a final response` as failures. Permission-denied or unsupported-capability replies are valid observations when the test is checking graceful degradation. Preserve the JSON report, capture relevant `adb logcat` output, and distinguish model/API failures from Android permission or carrier limitations. Run `./gradlew test` and `./gradlew :app:lintDebug` after code changes.
