# stepgrid-llm-kit

The parts you end up rewriting every time you put an on-device LLM into an Android app — and that
have nothing to do with which model you run. Extracted from [StepGrid](https://play.google.com/store/apps/details?id=co.stepgrid),
a shipping app that runs Gemma on-device with [LiteRT-LM](https://developers.google.com/edge/litert-lm);
the app depends on this repository, so what is here is what is running.

[한국어 README](./README.ko.md)

| Module | What it does | Needs Android |
|---|---|---|
| **`engine`** | One shared LiteRT-LM engine: GPU→CPU fallback ladder, retry-aware sampling, streamed and one-shot generation, and measurements of what actually happened | yes |
| `device-tier` | Decide which model size a device can run, from real specs (chip class + RAM + cores) | reading specs only |
| `resume` | Decide whether a half-finished download can be appended to, from what the server actually sent | no (pure JVM) |

Everything that *decides* is pure Kotlin and unit-tested on the JVM. Only the thin leaves that
read Android or call the native runtime need a device.

## Install

```kotlin
dependencies {
    implementation("io.github.wesley-product:llm-kit:0.1.0")   // all three modules
}
```

Or pick modules individually — `engine`, `device-tier`, `resume` — under the same group and
version. `resume` is pure JVM and needs no Android.

> **Status:** `0.1.0` is being prepared and is **not on Maven Central yet**. Until it is, clone this
> repository next to yours and let Gradle substitute the coordinate with the local build:
>
> ```kotlin
> // settings.gradle.kts
> includeBuild("../stepgrid-llm-kit") {
>     dependencySubstitution {
>         substitute(module("io.github.wesley-product:llm-kit")).using(project(":llm-kit"))
>         // If you depend on modules individually, substitute those too:
>         // substitute(module("io.github.wesley-product:engine")).using(project(":engine"))
>         // substitute(module("io.github.wesley-product:device-tier")).using(project(":device-tier"))
>         // substitute(module("io.github.wesley-product:resume")).using(project(":resume"))
>     }
> }
> ```
>
> The `implementation(...)` lines above stay as they are. This note disappears when the release lands.

`engine` pulls LiteRT-LM in as an `api` dependency. minSdk 26. No consumer ProGuard rules are
needed — the library uses no reflection or serialization, so R8 keeps exactly what you call.

**You bring the model file.** The library only ever asks for a path. Gemma models in LiteRT-LM
format (`.litertlm`) are documented at [LiteRT-LM](https://developers.google.com/edge/litert-lm)
and published under `litert-community` on Hugging Face; checking each model's license is on you.

## Putting the three together

Tier → download → run. Model ids, file names and URLs below are **yours to define** — the library
never sees them, only the path you resolve them to.

```kotlin
// 1. Which size can this device run?
val tier = tierOf(readDeviceSpecs(context))
val model = when (tier) {
    ModelTier.UNSUPPORTED -> return explainAndStop()
    ModelTier.LITE        -> Model("small",  "https://your.cdn/models/small.litertlm")   // e.g. a 0.6B
    ModelTier.STANDARD    -> Model("medium", "https://your.cdn/models/medium.litertlm")  // e.g. a 1.5B
    ModelTier.PRO         -> Model("large",  "https://your.cdn/models/large.litertlm")   // e.g. a 4B INT4
}

// 2. Download it, resuming safely (your loop; the library only decides append vs restart)
val target = File(context.filesDir, "${model.id}.litertlm")
val existing = target.length()
val conn = (URL(model.url).openConnection() as HttpURLConnection).apply {
    if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
}
val plan = ResumePlan.of(existing, conn.responseCode, conn.contentLengthLong)
FileOutputStream(target, plan.append).use { conn.inputStream.copyTo(it) }

// 3. Run it
engine.useModel(model.id)                       // engine's ModelSource maps "large" -> target.path
val chat = engine.startConversation("You are a walking companion.")
engine.sendStream(chat, "I keep circling the same thought.").collect { render(it) }
```

One engine per app. With Hilt:

```kotlin
@Module @InstallIn(SingletonComponent::class)
object LlmModule {
    @Provides @Singleton
    fun engine(@ApplicationContext ctx: Context, store: ModelStore): LlmEngine = LlmEngine(
        models = { id -> store.pathOf(id) },       // null until downloaded
        ioDispatcher = Dispatchers.IO,
        cacheDir = File(ctx.cacheDir, "litertlm").apply { mkdirs() }.path,
        memoryProbe = { readMemory(ctx) },
    )
}
```

## `engine`

LiteRT-LM's `Backend.GPU()` does not fall back to CPU when GPU initialization fails on a device
(missing OpenCL is the usual reason), and the context size you ask for may not fit that device's
memory. The engine tries a ladder so that neither leaves you with no engine:

```
GPU + 4096 → CPU + 4096 → GPU + 2048 → CPU + 2048 → GPU → CPU
```

```kotlin
val engine = LlmEngine(
    models = { id -> modelStore.pathOf(id) },       // you own model files; the engine only needs a path
    ioDispatcher = Dispatchers.IO,
    cacheDir = File(context.cacheDir, "litertlm").path,
    memoryProbe = { readMemory(context) },          // optional: measure what a build costs
    events = object : EngineEvents {
        override fun onEngineBuilt(info: EngineInfo) =
            Log.i(TAG, "up on ${info.compute}, maxTokens=${info.maxTokens}, rung ${info.rungIndex}, ${info.buildMillis}ms")
    },
)

engine.useModel("gemma-e2b")

// one-shot
val title = engine.generate("Summarize in one line: $text")

// multi-turn, streamed
val chat = engine.startConversation(systemInstruction = "You are a walking companion.")
engine.sendStream(chat, "I keep circling the same thought.").collect { textSoFar -> render(textSoFar) }
engine.close(chat)
```

### Retries that actually retry

The runtime's default seed is fixed. Ask the same prompt twice and you get the same text,
character for character — so a retry loop that re-asks on a bad answer never retries. Pass the
attempt number and the engine moves the seed by a prime stride from the second attempt on:

```kotlin
engine.generate(prompt, attempt = 0)   // reproducible, the answer you measured
engine.generate(prompt, attempt = 1)   // a different sample
```

### Per call, not per engine

Deterministic summaries and a chatty assistant from the same engine — no second instance.
**Copy the engine's sampler, don't construct a new one**: `Sampling(temperature = 0.0)` would
silently reset `topK`/`topP` to the class defaults instead of the values you configured.

```kotlin
val greedy = engine.limits.sampling.copy(temperature = 0.0)
engine.generate(prompt, sampling = greedy, maxChars = 300)

val chatty = engine.limits.sampling.copy(temperature = 0.9)
engine.startConversation(system, sampling = chatty)   // fixed for that conversation
```

### Lifecycle

- **One `LlmEngine` per app.** The model is gigabytes; with DI, make it a singleton.
- `engine.close(chat)` when the screen that owns the chat goes away. After `useModel()` switches
  models, any open `Chat` throws `StaleModelException` on its next `send` — open a new one.
- `engine.forget(id)` when you delete a model's file — if it was the one loaded, the engine is
  torn down so the memory is actually reclaimed.
- `engine.close()` when the engine's owner is destroyed. `close(chat)` after that is a no-op.
- If **every** rung of the ladder fails, `generate` / `startConversation` throw the last cause. That
  device cannot run this model: treat it like `device-tier`'s `UNSUPPORTED`. `EngineEvents.onEngineBuildAttemptFailed`
  tells you which rung failed and why.
- Everything that touches the runtime runs on the `ioDispatcher` you pass — including `useModel`,
  teardown, and every `EngineEvents` callback. Hop to the main thread yourself for UI.
- Cancelling the collector of a streamed reply stops delivery, asks the runtime to stop generating,
  and only then releases the engine — so the next call never starts while the previous one is still
  running. (The runtime's own `Flow` does nothing on cancellation; this library asks explicitly.)
- **That wait has a clock on it.** `cancelProcess()` promises nothing about when generation actually
  ends, and a runtime that drops the request would hold the engine — and every call queued behind it
  — for good; the user cancels and the app goes quiet. So the wait is bounded by
  `GenerationLimits.stopGraceMillis` (5s by default) and reported as `GenerationStats.stopWaitMillis`
  every time. Past the grace the engine is **quarantined**, not freed: a native callback may still be
  writing into that memory, so it is left allocated on purpose and every later call throws
  `EngineStuckException`. Build a new `LlmEngine`; the memory comes back with the process. A leaked
  engine is a bounded cost. A use-after-free is not.
- `chat.isUsable()` answers for all three reasons a conversation dies — interrupted part-way, model
  switched underneath it, engine closed or quarantined — so it agrees with what the next `send()`
  would throw instead of only knowing about the first.
- `engine.close()` is final. Every later call that would generate or change the model throws
  `EngineClosedException`. The teardown paths stay quiet instead — `close(chat)` reports a warning
  and `warmUp()` does nothing — because they are called from code that is already going away.

### It tells you what happened — and only what it knows

| | What you get | How it is known |
|---|---|---|
| `engine.currentEngine()` | `EngineInfo` — the backend and context cap that *actually* came up, which ladder rung, cold-start ms, memory delta | the rung that succeeded; a clock; your `memoryProbe` |
| `engine.lastGeneration()` | `GenerationStats` — ms to first token, total ms, chars, chunks, chars/s, whether the output cap hit | measured around each generation, counting only what you received |
| `chat.usage()` | `ContextUsage` — turns, chars in/out, token counts, fraction of the window used | chars are exact; **tokens only if you supply a `TokenCounter`, otherwise `null`** |

Characters are not tokens, and the ratio between them depends on the language and the text.
The engine will not guess for you.

### Every number is a parameter

```kotlin
LlmEngine(..., limits = GenerationLimits(
    maxContextTokens = 2048,      // hard cap every bundled model accepts
    roomyContextTokens = 4096,    // tried first; costs KV-cache memory
    maxGenChars = 1000,           // client-side stop for a model that never emits EOS
    seedStride = 7919,
    sampling = Sampling(topK = 16, topP = 0.7, temperature = 0.7),
))
```

The defaults are what StepGrid ships for Gemma E2B/E4B. They are documented in KDoc with the
reason each value was chosen; if you run a different model, expect to change them.

`streamMode` is the one setting you should not guess at. Chunks arrive either as deltas or as
cumulative snapshots, and **no amount of inspecting the text can tell them apart** — a model that
repeats a token sends the same chunk twice, which looks exactly like a snapshot of what came
before. The default (`StreamMode.DELTA`) is what LiteRT-LM 0.13.x does; set it explicitly if your
runtime differs, or you will silently lose repeated text.

## `device-tier`

```kotlin
when (tierOf(readDeviceSpecs(context))) {
    ModelTier.UNSUPPORTED -> explainAndStop()
    ModelTier.LITE        -> download(smallModel)     // your ids, your files
    ModelTier.STANDARD    -> download(mediumModel)
    ModelTier.PRO         -> download(largeModel)
}
```

Two axes are judged together — chip class and RAM/cores — because neither is trustworthy alone:
RAM says an 8 GB budget phone beats a 6 GB flagship; the chip alone puts a big model on a good
chip with too little RAM. Thresholds are parameters (`TierThresholds`); the defaults assume a
4B-INT4 / 1.5B / 0.6B lineup. With a single model, use it as a gate: anything but `UNSUPPORTED`.

Android has no API that says how fast a chip is — only a name string — so `classifyChip` is a
table of known patterns, and unknown chips are capped at `STANDARD`. The two directions of error
do not cost the same:

| Guess | What the user experiences |
|---|---|
| Too low | A lesser answer than the device could give — and they never know |
| Too high | A multi-GB download, then an app that crawls or crashes |

**Do not trust the table; fix it for the devices you actually see**, and read `DeviceSpecs.soc`
to find out what those were.

### Why reading and deciding are separate functions

`readDeviceSpecs(context)` reads Android; `tierOf(specs)` is a pure function. They used to be one
function — and so had no tests at all: a mid-range device with 6 GB RAM and 8 cores could only be
produced by faking `Build` and `ActivityManager`, and then the thing under test is the fake. The
same split is what made it possible to extract this into a library. Not being testable and not
being extractable were the same problem.

## `resume`

Requesting a byte range is not the same as receiving one. A CDN may ignore `Range`, a redirect
may drop the header, and the response is then a plain 200 from byte zero. Append that to the
partial file on disk and you get a file of the right size and the wrong contents — which loads
fine and fails deep inside the runtime, with nothing pointing back at the download.

```kotlin
val existing = target.length()
val conn = (URL(url).openConnection() as HttpURLConnection).apply {
    if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
}
val plan = ResumePlan.of(existing, conn.responseCode, conn.contentLengthLong)

FileOutputStream(target, plan.append).use { conn.inputStream.copyTo(it) }
// progress from plan.startAt; total is plan.total (null if unknown)
```

`plan.total` exists because a 206 reports only the *remaining* length. Use it as the total and a
nearly finished download shows 0% — to the user, exactly the restart resume was meant to avoid.
One plan per file; a multi-file model is just this, once per file.

## Design rules

- **Pure decisions, thin leaves.** `tierOf` / `readDeviceSpecs`, `engineLadder` / `LlmEngine`.
  If a rule cannot be tested on the JVM, the split is not finished.
- **Report facts; never present an estimate as one.**
- **Silent by default.** Nothing is written to Logcat unless you wire `EngineEvents`.
- **Explicit API.** `explicitApi()` is on; every public declaration has KDoc that says why.

## Versioning

SemVer. Before 1.0, minor versions may change public API; every such change is listed in
[CHANGELOG.md](./CHANGELOG.md). See [CONTRIBUTING.md](./CONTRIBUTING.md).

## Development

```bash
./gradlew :resume:test :device-tier:testDebugUnitTest :engine:testDebugUnitTest
```

## License

[Apache License 2.0](./LICENSE)
