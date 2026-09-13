# stepgrid-llm-kit

안드로이드에서 온디바이스 LLM 을 붙일 때 **모델 자체와 상관없이 매번 다시 짜게 되는 것**들을
떼어낸 것입니다. [StepGrid](https://play.google.com/store/apps/details?id=co.stepgrid) 에서
쓰고 있는 코드이고, 그 앱이 이 저장소를 의존성으로 받아 씁니다.

[English README](./README.md)

| 모듈 | 무엇 | 안드로이드 필요 |
|---|---|---|
| **`engine`** | LiteRT-LM 엔진 하나를 공유: GPU->CPU 폴백 사다리, 재청 시드, 스트리밍·일회성 생성, 그리고 실제로 무슨 일이 있었는지의 측정 | 예 |
| `device-tier` | 기기 사양(칩 등급 + RAM + 코어)으로 어느 크기의 모델을 줄지 결정 | 조회부만 |
| `resume` | 받다 만 큰 파일을 이어받아도 되는지, 서버가 실제로 보낸 것으로 판단 | 아니오 (순수 코틀린) |

**판단하는 코드는 전부 순수 코틀린이고 JVM 에서 유닛 테스트됩니다.** 안드로이드를 읽거나
네이티브 런타임을 부르는 얇은 끝부분만 기기가 필요합니다.

## 넣기

```kotlin
dependencies {
    implementation("io.github.wesley-product:llm-kit:0.1.0")   // 셋 전부
}
```

골라 쓰려면 개별 좌표도 있습니다 — `engine` · `device-tier` · `resume`. `resume` 은 순수 JVM 이라
안드로이드 없이도 됩니다.

`engine` 이 LiteRT-LM 을 `api` 로 끌고 옵니다. minSdk 26. 소비자용 ProGuard 규칙은 필요 없습니다 —
리플렉션도 직렬화도 안 써서 R8 은 여러분이 부르는 것만 남깁니다.

> **상태:** `0.1.0` 은 준비 중이고 아직 Maven Central 에 없습니다. 그 전까지는 이 저장소를 옆에 받아
> Gradle 이 좌표를 로컬 빌드로 갈음하게 합니다. `implementation(...)` 줄은 그대로 둡니다.
>
> ```kotlin
> // settings.gradle.kts
> includeBuild("../stepgrid-llm-kit") {
>     dependencySubstitution {
>         substitute(module("io.github.wesley-product:llm-kit")).using(project(":llm-kit"))
>     }
> }
> ```
>
> 올라가면 이 줄이 사라집니다.

**모델 파일은 여러분이 준비합니다.** 이 라이브러리는 경로만 받습니다. LiteRT-LM 형식(`.litertlm`)의
Gemma 모델은 [LiteRT-LM 문서](https://developers.google.com/edge/litert-lm)와 Hugging Face 의
`litert-community` 에서 구할 수 있고, 각 모델의 라이선스를 확인하는 것도 여러분 몫입니다.

## 셋을 이어 붙이면

등급 판정 → 받기 → 돌리기. 모델 id·파일명·URL 은 **여러분이 정합니다** — 라이브러리는 그것들을
모르고, 여러분이 풀어 준 경로만 받습니다.

```kotlin
// 1. 이 기기는 어느 크기를 돌릴 수 있나
val tier = tierOf(readDeviceSpecs(context))
val model = when (tier) {
    ModelTier.UNSUPPORTED -> return explainAndStop()
    ModelTier.LITE        -> Model("small",  "https://your.cdn/models/small.litertlm")   // 예: 0.6B
    ModelTier.STANDARD    -> Model("medium", "https://your.cdn/models/medium.litertlm")  // 예: 1.5B
    ModelTier.PRO         -> Model("large",  "https://your.cdn/models/large.litertlm")   // 예: 4B INT4
}

// 2. 안전하게 이어받기 (루프는 여러분 것, 라이브러리는 이어 붙일지 다시 받을지만 정합니다)
val target = File(context.filesDir, "${model.id}.litertlm")
val existing = target.length()
val conn = (URL(model.url).openConnection() as HttpURLConnection).apply {
    if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
}
val plan = ResumePlan.of(existing, conn.responseCode, conn.contentLengthLong)
FileOutputStream(target, plan.append).use { conn.inputStream.copyTo(it) }

// 3. 돌리기
engine.useModel(model.id)                       // 엔진의 ModelSource 가 "large" -> target.path 로 풉니다
val chat = engine.startConversation("당신은 산책 동행입니다.")
engine.sendStream(chat, "같은 생각이 계속 맴돕니다.").collect { render(it) }
```

엔진은 앱에 하나. Hilt 라면:

```kotlin
@Module @InstallIn(SingletonComponent::class)
object LlmModule {
    @Provides @Singleton
    fun engine(@ApplicationContext ctx: Context, store: ModelStore): LlmEngine = LlmEngine(
        models = { id -> store.pathOf(id) },       // 아직 안 받았으면 null
        ioDispatcher = Dispatchers.IO,
        cacheDir = File(ctx.cacheDir, "litertlm").apply { mkdirs() }.path,
        memoryProbe = { readMemory(ctx) },
    )
}
```

## `engine` — 추론·스트리밍

LiteRT-LM 의 `Backend.GPU()` 는 **기기에서 GPU 초기화가 실패해도 CPU 로 내려가 주지 않습니다**(OpenCL 이
없는 기기가 흔한 원인입니다). 요청한 컨텍스트 크기가 그 기기 메모리에 안 들어갈 수도 있습니다.
어느 쪽이든 엔진이 없는 상태로 남지 않게 사다리를 탑니다.

```
GPU + 4096 -> CPU + 4096 -> GPU + 2048 -> CPU + 2048 -> GPU -> CPU
```

```kotlin
val engine = LlmEngine(
    models = { id -> modelStore.pathOf(id) },       // 모델 파일은 앱이 관리합니다. 엔진은 경로만 필요합니다
    ioDispatcher = Dispatchers.IO,
    cacheDir = File(context.cacheDir, "litertlm").path,
    memoryProbe = { readMemory(context) },          // 선택: 엔진을 올리는 데 메모리가 얼마나 드는지 잽니다
    events = object : EngineEvents {
        override fun onEngineBuilt(info: EngineInfo) =
            Log.i(TAG, "up on ${info.compute}, maxTokens=${info.maxTokens}, rung ${info.rungIndex}, ${info.buildMillis}ms")
    },
)

engine.useModel("gemma-e2b")

// 일회성
val title = engine.generate("한 줄로 요약: $text")

// 멀티턴 스트리밍
val chat = engine.startConversation(systemInstruction = "당신은 산책 동행입니다.")
engine.sendStream(chat, "같은 생각이 계속 맴돕니다.").collect { textSoFar -> render(textSoFar) }
engine.close(chat)
```

### 다시 청하면 정말 다른 답이 나오게

런타임의 기본 시드는 고정입니다. 같은 프롬프트를 두 번 청하면 **글자 하나까지 같은 답**이 돌아와서,
답이 나쁘면 다시 청하는 코드가 있어도 다시 청해지지 않습니다. 시도 번호를 넘기면 두 번째부터
시드를 소수 간격으로 옮깁니다.

```kotlin
engine.generate(prompt, attempt = 0)   // 재현되는, 지금까지 재 온 그 답
engine.generate(prompt, attempt = 1)   // 다른 표본
```

### 호출마다 다르게

요약은 결정론적으로, 대화는 자유롭게 — 엔진을 둘 둘 필요 없습니다.

**엔진의 샘플러를 복사해서 바꾸세요. 새로 만들면 안 됩니다** — `Sampling(temperature = 0.0)` 은
여러분이 맞춰 둔 `topK`/`topP` 가 아니라 클래스 기본값으로 조용히 되돌아갑니다.

```kotlin
val greedy = engine.limits.sampling.copy(temperature = 0.0)
engine.generate(prompt, sampling = greedy, maxChars = 300)

val chatty = engine.limits.sampling.copy(temperature = 0.9)
engine.startConversation(system, sampling = chatty)   // 대화 단위로 고정됩니다
```

### 수명

- `LlmEngine` 은 **앱에 하나**입니다(엔진이 GB 단위라). DI 를 쓰면 싱글턴으로.
- `Chat` 은 화면이 사라질 때 `engine.close(chat)`. 모델을 바꾸면(`useModel`) 열려 있던 `Chat` 은
  다음 `send` 에서 `StaleModelException` 을 던집니다 — 새 대화를 여세요.
- 엔진의 주인이 죽을 때 `engine.close()`. 그 뒤의 `close(chat)` 은 아무 일도 하지 않습니다.
- 사다리 여섯 칸이 **전부** 실패하면 `generate`/`startConversation` 이 마지막 예외를 던집니다.
  그 기기는 이 모델을 못 돌리는 것이니 `device-tier` 의 `UNSUPPORTED` 와 같게 다루세요.
  `EngineEvents.onEngineBuildAttemptFailed` 로 어느 칸이 왜 실패했는지 받을 수 있습니다.
- 런타임을 만지는 모든 호출은 넘긴 `ioDispatcher` 에서 돕니다 — `useModel`·해제·모든 `EngineEvents` 콜백까지.
  UI 를 만지려면 메인으로 직접 옮기세요.
- 스트리밍 수집을 취소하면 전달이 멈추고 엔진이 풀립니다. 그 순간 런타임이 안에서 생성을 멈추는지는
  런타임의 동작이고 이 라이브러리가 보장하는 것이 아닙니다.

### 무슨 일이 있었는지 말해 줍니다 — 확실히 아는 것만

| | 무엇을 받나 | 어떻게 아나 |
|---|---|---|
| `engine.currentEngine()` | `EngineInfo` — **실제로** 뜬 백엔드와 컨텍스트 크기, 사다리 몇 번째 칸, 콜드 스타트 ms, 메모리 델타 | 성공한 칸 · 시계 · 넘긴 `memoryProbe` |
| `engine.lastGeneration()` | `GenerationStats` — 첫 토큰까지 ms, 전체 ms, 글자 수, 조각 수, 초당 글자, 출력 상한에 걸렸나 | 생성마다 측정 |
| `chat.usage()` | `ContextUsage` — 턴 수, 들어간/나온 글자 수, 토큰 수, 창 대비 비율 | 글자는 정확. **토큰은 `TokenCounter` 를 줬을 때만, 아니면 `null`** |

글자 수는 토큰 수가 아니고, 그 비율은 언어와 문장에 따라 다릅니다. 엔진은 대신 추측해 주지 않습니다.

### 숫자는 전부 인자입니다

```kotlin
LlmEngine(..., limits = GenerationLimits(
    maxContextTokens = 2048,      // 번들된 모든 모델이 받는 상한
    roomyContextTokens = 4096,    // 먼저 시도. KV 캐시 메모리를 먹습니다
    maxGenChars = 1000,           // EOS 를 안 내는 모델을 멈추는 클라이언트 쪽 상한
    seedStride = 7919,
    sampling = Sampling(topK = 16, topP = 0.7, temperature = 0.7),
))
```

기본값은 StepGrid 가 Gemma E2B/E4B 로 내보내는 값이고, 각 값을 왜 골랐는지는 KDoc 에 있습니다.
다른 모델이면 바꿔야 한다고 생각하고 시작하세요.

## `resume` — 이어받기 판단

범위를 **요청하는 것과 받는 것은 다릅니다.** CDN 이 `Range` 를 무시할 수 있고 리다이렉트에서
헤더가 빠질 수도 있는데, 그러면 응답은 첫 바이트부터 오는 평범한 200 입니다.

그걸 디스크에 있던 조각 뒤에 이어 붙이면 **크기는 맞고 내용은 깨진 파일**이 됩니다.
GB 단위 모델이 로드까지 되고 나서 런타임 깊은 곳에서 실패하는데, 거기서 원인을 되짚어 올
실마리가 없습니다.

```kotlin
val existing = target.length()
val conn = (URL(url).openConnection() as HttpURLConnection).apply {
    if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
}

val plan = ResumePlan.of(existing, conn.responseCode, conn.contentLengthLong)

FileOutputStream(target, plan.append).use { out -> conn.inputStream.copyTo(out) }
// 진행률은 plan.startAt 부터, 전체는 plan.total (모르면 null)
```

`plan.total` 이 따로 있는 이유는 **206 응답이 남은 양만 알려주기** 때문입니다. 그대로 전체로
쓰면 거의 다 받은 다운로드가 0퍼센트 근처로 돌아가고, 그건 사용자 눈에 처음부터 다시 받는
것으로 보입니다 — 이어받기로 고치려던 바로 그 인상입니다.

## `device-tier` — 기기 등급 판단

```kotlin
val tier = tierOf(readDeviceSpecs(context))

when (tier) {
    ModelTier.UNSUPPORTED -> 안내만보여준다()
    ModelTier.LITE -> 받는다(작은모델)
    ModelTier.STANDARD -> 받는다(중간모델)
    ModelTier.PRO -> 받는다(큰모델)
}
```

**두 축을 같이 봅니다** — 칩 등급과 RAM/코어입니다. 어느 하나도 혼자서는 못 믿습니다.
RAM 만 보면 8GB 보급형이 6GB 플래그십을 이긴다고 판단하고, 칩만 보면 좋은 칩인데 RAM 이
모자란 기기에 큰 모델을 올립니다.

경계값은 인자로 바꿉니다. 기본값은 4B INT4 / 1.5B / 0.6B 조합 기준이라 **다른 모델을 쓰면
그대로 맞지 않습니다.**

```kotlin
tierOf(specs, TierThresholds(ramProGb = 12.0))
```

### 모르는 칩을 어떻게 다루나

안드로이드에는 **칩이 얼마나 빠른지 알려주는 API 가 없습니다.** 있는 건 이름 문자열뿐이라
아는 패턴에 대보는 수밖에 없고, 그래서 이 표는 새 칩이 나오면 그날부터 낡습니다.

`ChipClass.UNKNOWN` 은 RAM 과 코어만으로 `STANDARD` 까지 갈 수 있고 `PRO` 로는 못 갑니다.
두 방향의 실수가 대가가 다르기 때문입니다.

| 실수 | 사용자가 겪는 것 |
|---|---|
| 낮게 잡았다 | 더 좋은 답을 받을 수 있었는데 못 받는다. **본인은 모른다** |
| 높게 잡았다 | GB 단위를 다 받은 뒤에 **느리거나 죽는다** |

**표를 그대로 믿지 마세요.** 안전한 쪽으로 떨어지도록 판단을 짜 둔 것이지, 표가 맞아서 되는
게 아닙니다. 쓰시는 기기 목록에 맞게 `classifyChip` 을 고쳐 쓰시는 편이 낫습니다.

## 왜 판단과 조회가 갈라져 있나

`readDeviceSpecs(context)` 는 안드로이드를 읽고, `tierOf(specs)` 는 **순수 함수**입니다.

원래는 한 함수였습니다. 그래서 **테스트가 하나도 없었습니다** — RAM 6GB 에 코어 8개인 미들급
기기를 만들려면 `Build` 와 `ActivityManager` 를 흉내내야 하고, 그러면 검사하는 것이 판단이
아니라 흉내가 됩니다.

떼어내려니 그게 걸렸고, 나누고 나서야 테스트가 붙었습니다. 테스트가 안 써지는 것과 떼어낼 수
없는 것이 **같은 원인**이었습니다.

## 개발

```bash
./gradlew :resume:test :device-tier:testDebugUnitTest
```

## 라이선스

[Apache License 2.0](./LICENSE)
