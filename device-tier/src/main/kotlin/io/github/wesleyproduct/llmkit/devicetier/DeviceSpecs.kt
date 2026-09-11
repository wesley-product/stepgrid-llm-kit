package io.github.wesleyproduct.llmkit.devicetier

/**
 * 등급을 정하는 데 쓰이는 기기 사양. **값만 담는다.**
 *
 * 안드로이드에서 읽어 오는 것과 그 값으로 판단하는 것을 갈라 두려고 따로 있는 타입이다.
 * 붙여 두면 램 6GB 에 코어 8개인 미들급 기기를 테스트에서 만들 수가 없고, 그러면 판단에
 * 테스트를 못 붙인다. 실제로 그래서 오래 테스트가 없었다.
 *
 * 안드로이드에서 읽으려면 [read] 를, 그렇지 않으면 직접 만들어 넘기면 된다.
 */
public data class DeviceSpecs(
    /** 전체 RAM. 앱이 쓸 수 있는 양이 아니라 기기가 가진 양이다. */
    val ramGb: Double,
    /** 지금 쓸 수 있는 코어 수. 절전 상태에서는 실제보다 적게 나올 수 있다. */
    val cores: Int,
    val is64Bit: Boolean,
    /** 시스템이 스스로 저사양이라고 표시한 기기. */
    val lowRam: Boolean,
    val chip: ChipClass,
    /**
     * 알아본 칩 이름 그대로. 등급 판단에는 안 쓰이지만([tierOf] 는 [chip] 만 본다),
     * 사용자에게 보여 주거나 신고에 붙일 때 필요하다 — 「모르는 칩」으로 떨어진 기기가
     * 실제로 무엇이었는지는 이 값이 없으면 영영 알 수 없다.
     */
    val soc: String = "",
)

/**
 * 칩 등급. 안드로이드에는 **칩이 얼마나 빠른지 알려주는 API 가 없어서**, 이름 문자열을
 * 아는 패턴에 대보는 것 말고는 방법이 없다([classifyChip]).
 *
 * 그래서 [UNKNOWN] 이 예외가 아니라 정상적으로 자주 나온다. 새 칩이 나오면 그날부터 그렇다.
 * 모르는 칩을 어떻게 다룰지가 이 라이브러리에서 제일 중요한 결정이다 — [tierOf] 를 볼 것.
 */
public enum class ChipClass { HIGH, MID, LOW, UNKNOWN }
