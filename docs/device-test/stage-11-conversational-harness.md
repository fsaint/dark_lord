# Stage 11 — Conversational model harness

The prototype now routes SMS, assistant, and voice transcripts through one bounded model loop:

1. The owner enters an OpenAI API key in Dark Lord’s local setup screen. The key is encrypted with Android Keystore and is never included in diagnostics, messages, or audit output.
2. The Responses API receives the user text, scoped tool names, and bounded conversation transcript.
3. Tool calls are executed through the existing scope router. The harness persists checkpoints and resumes after interruption, with an eight-turn safety budget.
4. One final response is sent over SMS or spoken with Android TextToSpeech.

## Device smoke test

- Open Dark Lord and provision an owner.
- Enter an `sk-…` key and tap **Save model key**.
- Trigger Dark Lord from the Side button or send an SMS such as `What is my battery level?`.
- Confirm one final reply arrives; a missing key produces a setup message rather than a crash.
- Start voice capture, speak the same request, and confirm the answer is spoken aloud.

The first release uses HTTPS JSON Responses calls and Android SpeechRecognizer/TextToSpeech. Streaming voice and the official Kotlin MCP transport remain follow-up work after this end-to-end path is validated.
