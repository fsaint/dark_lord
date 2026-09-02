# Local chat API: 50-request device suite

This black-box suite sends natural-language requests through the same `/chat` endpoint used for development testing. It verifies tool selection, multi-step orchestration, Python bindings, artifact handoffs, and safe explanations.

The default run excludes SMS, calling, and device-changing requests. Those three cases are marked as side effects and require an explicit opt-in.

## Run

Enable **Local chat API** in Dark Lord, then run:

```bash
python3 tools/chat_api_test.py
```

The default URL is `http://dark-lord.curl-newton.ts.net:8765`. Override it with `--url`. Results are printed live and saved to `chat-api-report.json`.

Run one case:

```bash
python3 tools/chat_api_test.py --case take_picture
```

Run the side-effect cases only on a disposable test setup:

```bash
python3 tools/chat_api_test.py --include-side-effects \
  --case dangerous_sms --case dangerous_call --case change_volume
```

The suite treats an HTTP 200 response with non-empty text as a transport-level pass. Review the saved replies and device audit log for semantic correctness. A pass does not mean a permission-dependent action succeeded.
