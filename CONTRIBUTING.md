# Contributing

Thanks for looking. This is a small library maintained by one person alongside the app it came
from, so the bar is: **keep it small, keep the decisions pure, keep the tests green.**

## What belongs here

Things you end up rewriting every time you put an on-device LLM into an Android app, and that do
not depend on *which* model — resuming a large download correctly, choosing a model size from
device specs, running LiteRT-LM with fallback and measurement.

What does not: prompts, safety filtering, model catalogs, download UI. Those are the app's.

## Ground rules

- **Decisions are pure Kotlin and unit-tested.** If a rule needs `Context`, split it: one function
  reads Android, one function decides. `tierOf` / `readDeviceSpecs` and `engineLadder` / `LlmEngine`
  are the pattern. If you cannot test a decision on the JVM, the split is not done yet.
- **Report what you know; never estimate and present it as fact.** Token counts are `null` without a
  tokenizer. Memory is a measured delta or absent. Keep it that way.
- **Silent by default.** The library writes nothing to Logcat unless the host wires `EngineEvents`.
- **Public API is explicit** (`explicitApi()` is on). Every `public` declaration gets KDoc that says
  *why*, not just what.
- **Numbers are parameters.** Context sizes, caps, thresholds, seeds — put them in a config type
  with the shipping value as the default, and say in KDoc where that default came from.

## Running the tests

```bash
./gradlew :resume:test :device-tier:testDebugUnitTest :engine:testDebugUnitTest
```

`LlmEngine` itself and `readDeviceSpecs` / `readMemory` touch the native runtime or Android and
are exercised on a device by the consuming app, not here.

## Commits and pull requests

- English, imperative, and say **why** in the body — the `git log` is read by people who were not
  there. Prefix with the module: `engine:`, `device-tier:`, `resume:`, or `build:`/`docs:`.
- One change per PR. Add a line to `CHANGELOG.md` under **Unreleased**.
- Before 1.0 the public API may still move; a PR that changes it must list the change under
  **Changed** in the changelog.

## Reporting a problem

Open an issue with the device model, Android version, the model file you used, and — if it is an
engine problem — the `EngineInfo` your app logged (`onEngineBuilt`) and any
`onEngineBuildAttemptFailed` causes. That is usually enough to reproduce.

## Releasing

See [RELEASING.md](./RELEASING.md). The short version: a version whose engine has never run on a
real device does not get released.

## License

By contributing you agree your contribution is licensed under the [Apache License 2.0](./LICENSE).
