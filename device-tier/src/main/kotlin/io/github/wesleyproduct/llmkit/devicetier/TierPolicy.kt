package io.github.wesleyproduct.llmkit.devicetier

/**
 * 이 기기에 어느 크기의 모델을 줄 것인가.
 *
 * 이름이 아니라 **뜻으로** 읽어야 한다. 어느 모델이 어느 등급인지는 쓰는 쪽이 정한다.
 */
public enum class ModelTier {
    /** 어떤 모델도 올리지 않는다. */
    UNSUPPORTED,

    /** 제일 작은 모델만. */
    LITE,

    /** 중간 모델. */
    STANDARD,

    /** 제일 큰 모델. */
    PRO,
}

/**
 * 등급을 가르는 경계값.
 *
 * 기본값은 **4B INT4 / 1.5B / 0.6B 조합**을 기준으로 정한 것이라, 다른 모델을 쓰면 그대로
 * 맞지 않는다. 앱에 있을 때는 상수였는데 라이브러리로 꺼내면서 인자로 바꿨다 — 남이 쓸 코드에서
 * 이 숫자들이 고정이면 쓸모가 없다.
 */
public data class TierThresholds(
    /** 이 아래는 어떤 모델도 못 돌린다. */
    val ramMinGb: Double = 4.0,
    /** 중간 모델에 필요한 RAM. */
    val ramStandardGb: Double = 6.0,
    /** 제일 큰 모델에 필요한 RAM. */
    val ramProGb: Double = 8.0,
    /** 칩을 못 알아봤을 때 대신 보는 코어 수. */
    val coresMin: Int = 6,
)

/**
 * [DeviceSpecs] 로 등급을 정한다. **순수 함수다** — 안드로이드도, 컨텍스트도, 시간도 안 본다.
 *
 * 두 축을 같이 보는 이유는 어느 하나도 혼자서는 못 믿기 때문이다. 램만 보면 램 8GB 짜리
 * 보급형이 램 6GB 플래그십을 이긴다고 판단하고, 칩만 보면 좋은 칩인데 램이 모자란 기기에
 * 큰 모델을 올린다.
 *
 * ### 모르는 칩을 어떻게 다루나
 *
 * [ChipClass.UNKNOWN] 은 램과 코어만으로 [ModelTier.STANDARD] 까지 갈 수 있고 [ModelTier.PRO]
 * 로는 **못 간다.** 두 방향의 실수가 대가가 다르기 때문이다 — 낮게 잡으면 사용자는 더 좋은 답을
 * 못 받지만 그 사실을 모르고, 높게 잡으면 GB 단위를 다 받은 뒤에 느리거나 죽는다.
 * 뒤쪽이 훨씬 비싸서 모를 때는 낮은 쪽으로 기운다.
 */
public fun tierOf(
    specs: DeviceSpecs,
    thresholds: TierThresholds = TierThresholds(),
): ModelTier {
    // 1. 하드 게이트. 32비트나 시스템이 저사양이라고 표시한 기기는 여기서 끝난다.
    if (!specs.is64Bit || specs.lowRam || specs.ramGb < thresholds.ramMinGb) {
        return ModelTier.UNSUPPORTED
    }

    // 2. 제일 큰 모델은 확실할 때만 준다 — 아는 플래그십 + 넉넉한 램 + 코어.
    if (specs.chip == ChipClass.HIGH &&
        specs.ramGb >= thresholds.ramProGb &&
        specs.cores >= thresholds.coresMin
    ) {
        return ModelTier.PRO
    }

    // 3. 약한 칩은 램이 아무리 많아도 중간 모델을 안 준다. 램은 넉넉한데 칩이 약한 기기에서
    //    답이 견디기 힘들 만큼 느리게 나온 적이 있다 — 숫자로는 통과하던 기기였다.
    if (specs.chip == ChipClass.LOW) return ModelTier.LITE

    // 4. 중간 모델. 둘 중 하나면 통과다.
    //    뒤쪽이 코어 수를 안 보는 것은 개수가 성능을 잘 대변하지 못해서다 — 여덟 코어라도 넷이
    //    저전력이면 실제로 일하는 건 넷이고, 절전 상태에서 물으면 켜져 있는 것만 세어 준다.
    //    칩을 알아본 시점에 이미 더 나은 근거를 쥔 것이라 코어는 모르는 칩에만 쓴다.
    val standard = (specs.ramGb >= thresholds.ramStandardGb && specs.cores >= thresholds.coresMin) ||
        (specs.chip == ChipClass.HIGH && specs.ramGb >= thresholds.ramStandardGb)

    return if (standard) ModelTier.STANDARD else ModelTier.LITE
}

/**
 * 칩 이름을 등급으로 옮긴다.
 *
 * **이 표는 낡는다.** 새 칩이 나오면 그날부터 [ChipClass.UNKNOWN] 으로 떨어진다.
 * 그게 안전한 쪽으로 떨어지도록 [tierOf] 를 짜 둔 것이지, 표가 맞아서 되는 게 아니다.
 * 쓰시는 분의 기기 목록에 맞게 고쳐 쓰시는 편이 낫다.
 *
 * @param identifiers 칩을 가리킬 만한 문자열들. 안드로이드에서는 `Build.SOC_MODEL` ·
 *   `Build.HARDWARE` · `Build.BOARD` 를 다 넘기면 된다 — 하나만으로는 자주 빈다.
 */
public fun classifyChip(vararg identifiers: String?): ChipClass {
    val id = identifiers.filterNotNull().joinToString(" ").lowercase()

    val high = Regex(
        "tensor|sm8[0-9]{3}|sdm8[0-9]{2}|msm8998|snapdragon ?8|8 ?gen|888|870|865|860|855|845|" +
            "exynos ?2[0-9]{3}|exynos ?99[0-9]|exynos ?98[0-9]{2}|990|" +
            "dimensity ?9[0-9]{3}|dimensity ?8[0-9]{3}",
    )
    val mid = Regex(
        "sm7[0-9]{3}|snapdragon ?7|sd7|778|782|765|768|750|732|730|720|712|710|" +
            "exynos ?1[0-9]{3}|exynos ?9(6|7|8)[0-9]|dimensity ?[67][0-9]{3}|helio ?g[0-9]{2}|mt68[0-9]{2}",
    )
    val low = Regex(
        "sm[46][0-9]{3}|snapdragon ?[46]|sd6|sd4|6[0-9]{2}|4[0-9]{2}|helio ?[ap]|unisoc|" +
            "spreadtrum|mt67[0-9]{2}|mt65[0-9]{2}|exynos ?7[0-9]{2}|exynos ?85[0-9]",
    )

    return when {
        high.containsMatchIn(id) -> ChipClass.HIGH
        mid.containsMatchIn(id) -> ChipClass.MID
        low.containsMatchIn(id) -> ChipClass.LOW
        else -> ChipClass.UNKNOWN
    }
}
