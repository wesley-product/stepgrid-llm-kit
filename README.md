# stepgrid-llm-kit

안드로이드에서 온디바이스 LLM 을 붙일 때 **모델 자체와 상관없이 매번 다시 짜게 되는 것**들을
떼어낸 것입니다. [StepGrid](https://play.google.com/store/apps/details?id=co.stepgrid) 에서
쓰고 있는 코드이고, 그 앱이 이 저장소를 의존성으로 받아 씁니다.

두 가지가 들어 있습니다. 둘 다 **틀렸을 때 조용히 비싼** 종류라 테스트를 두껍게 붙였습니다.

| 모듈 | 무엇 | 안드로이드 필요 |
|---|---|---|
| `resume` | 받다 만 큰 파일을 이어받아도 되는지 판단 | 아니오 (순수 코틀린) |
| `device-tier` | 기기 사양으로 어느 크기의 모델을 줄지 결정 | 일부만 |

## 넣기

```kotlin
dependencies {
    implementation("io.github.wesley-product:resume:0.1.0")
    implementation("io.github.wesley-product:device-tier:0.1.0")
}
```

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
