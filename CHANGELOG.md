# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow [SemVer](https://semver.org/).
Before 1.0, minor versions may change public API — each such change is listed under **Changed**.

## [Unreleased]

## [0.1.0] — unreleased

First release. Everything here was extracted from a shipping Android app that runs Gemma on-device
with LiteRT-LM; each piece exists because the app hit the problem it solves.

Verified on a Galaxy S25 (SM-S938N, Android 16, SM8750) with Gemma E2B. The engine came up on the
first ladder rung (GPU, 4096-token context) in 7.2 s using 315 MB of native heap. Streamed replies
arrived as 41–50 chunks of 89–95 characters, first chunk at 0.5–1.6 s, 2.4–3.4 s in total, with
context carried correctly across turns. Roughly two characters per chunk is what settles the
`StreamMode` question for this runtime: treating those as cumulative snapshots would have produced
nonsense, and it did not.

### Added

- **`llm-kit`** — umbrella artifact: one dependency that brings in the three modules below.
  Each module stays separately published for à-la-carte use.
- **`engine`** — one shared LiteRT-LM engine behind a mutex.
  - GPU→CPU fallback ladder that also steps down the context size
    (GPU+4096 → CPU+4096 → GPU+2048 → CPU+2048 → GPU → CPU). LiteRT-LM does not fall back on its own.
  - Retry-aware sampling: attempt 0 keeps the base sampler; later attempts move the seed by a prime
    stride, because a retry with the runtime's fixed default seed returns the same text.
  - Streamed (`Flow<String>`, cumulative text) and one-shot generation; multi-turn `Chat` with a
    stale-conversation guard after a model switch.
  - Streaming that stops when you stop: cancelling a collector, or hitting the output cap,
    asks the runtime to stop generating before the engine is released. The runtime's own
    `Flow` does nothing on cancellation, so without this the previous generation would still
    be running when the next request acquired the engine.
  - `StreamMode` — whether chunks are deltas or cumulative snapshots is **stated**, not
    guessed. A model that repeats a token emits the same chunk twice, which no heuristic can
    tell apart from a snapshot; guessing dropped the repetition. Default matches LiteRT-LM 0.13.x.
  - The output cap applies to `send()` too, not just the streamed paths: a model that never
    emits EOS would otherwise hold the engine — and every model switch and teardown queued
    behind it — for as long as it kept talking.
  - A closed engine stays closed: every generating or model-changing call throws
    `EngineClosedException` instead of half-working with a cancelled chat-closing scope. Teardown
    stays quiet — `close(chat)` warns, `warmUp()` does nothing — since both are called on the way out.
  - Observability that reports only what is known for certain: `EngineInfo` (backend, context cap,
    ladder rung, build time, memory delta), `GenerationStats` (time to first token, total, chars,
    chunks, cap hit), `ContextUsage` (exact chars; token counts only with a supplied `TokenCounter`,
    otherwise `null`), `EngineEvents` callbacks (silent by default).
  - `GenerationLimits` / `Sampling` — every number is a parameter, not a constant.
  - Per-call `sampling` and `maxChars` on `generate` / `generateStream`, per-conversation `sampling`
    on `startConversation` — deterministic summaries and a chatty assistant from one engine.
  - `LlmEngine.close()` to tear the whole engine down when its owner is destroyed.
  - Threading contract: everything that touches the runtime — model switches and teardown
    included — runs on the engine's `ioDispatcher`, and so does every `EngineEvents` callback.
    Cancellation is never swallowed by the fallback ladder; JVM `Error`s stop it immediately.
    A failure while closing a chat is reported via `onWarning`, never thrown into the process.
    `warmUp()` follows the same discipline. A streamed call that fails before generating
    anything (e.g. `StaleModelException`) leaves the chat's `ContextUsage` and the stats untouched.
- **`device-tier`** — pick a model size from real device specs: `readDeviceSpecs(context)` (Android)
  and `tierOf(specs, thresholds)` (pure). Chip class and RAM/cores are judged together; unknown chips
  cap at `STANDARD`. `DeviceSpecs.soc` keeps the raw chip name so unknown devices can be identified later.
- **`resume`** — `ResumePlan.of(existing, responseCode, contentLength)` decides whether a partial
  download may be appended to (206) or must restart (200 — the server ignored `Range`), and restores
  the true total so progress does not jump back to 0%. Pure JVM, no Android dependency.

[Unreleased]: https://github.com/wesley-product/stepgrid-llm-kit/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/wesley-product/stepgrid-llm-kit/releases/tag/v0.1.0
