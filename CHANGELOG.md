# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow [SemVer](https://semver.org/).
Before 1.0, minor versions may change public API — each such change is listed under **Changed**.

## [Unreleased]

## [0.1.0] — unreleased

First release. Everything here was extracted from a shipping Android app that runs Gemma on-device
with LiteRT-LM; each piece exists because the app hit the problem it solves.

### Added

- **`engine`** — one shared LiteRT-LM engine behind a mutex.
  - GPU→CPU fallback ladder that also steps down the context size
    (GPU+4096 → CPU+4096 → GPU+2048 → CPU+2048 → GPU → CPU). LiteRT-LM does not fall back on its own.
  - Retry-aware sampling: attempt 0 keeps the base sampler; later attempts move the seed by a prime
    stride, because a retry with the runtime's fixed default seed returns the same text.
  - Streamed (`Flow<String>`, cumulative text) and one-shot generation; multi-turn `Chat` with a
    stale-conversation guard after a model switch.
  - `StreamAccumulator` — merges delta or cumulative-snapshot chunks transparently.
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
- **`device-tier`** — pick a model size from real device specs: `readDeviceSpecs(context)` (Android)
  and `tierOf(specs, thresholds)` (pure). Chip class and RAM/cores are judged together; unknown chips
  cap at `STANDARD`. `DeviceSpecs.soc` keeps the raw chip name so unknown devices can be identified later.
- **`resume`** — `ResumePlan.of(existing, responseCode, contentLength)` decides whether a partial
  download may be appended to (206) or must restart (200 — the server ignored `Range`), and restores
  the true total so progress does not jump back to 0%. Pure JVM, no Android dependency.

[Unreleased]: https://github.com/wesley-product/stepgrid-llm-kit/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/wesley-product/stepgrid-llm-kit/releases/tag/v0.1.0
