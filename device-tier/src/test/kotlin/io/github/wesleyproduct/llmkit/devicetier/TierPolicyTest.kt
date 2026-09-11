package io.github.wesleyproduct.llmkit.devicetier

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * 이 파일이 이 라이브러리가 존재하는 이유의 절반이다.
 *
 * 판단이 안드로이드 조회와 한 함수에 붙어 있던 동안에는 이 테스트를 **쓸 수가 없었다.**
 * 램 6GB 에 코어 8개인 미들급 기기를 만들려면 `Build` 와 `ActivityManager` 를 흉내내야 하고,
 * 그러면 검사하는 것이 내 판단이 아니라 내 흉내가 된다.
 */
class TierPolicyTest {

    private fun specs(
        ramGb: Double = 8.0,
        cores: Int = 8,
        is64Bit: Boolean = true,
        lowRam: Boolean = false,
        chip: ChipClass = ChipClass.HIGH,
    ) = DeviceSpecs(ramGb, cores, is64Bit, lowRam, chip)

    // --- 하드 게이트 ---

    @Test
    fun `32비트 기기는 아무것도 못 돌린다`() {
        assertEquals(ModelTier.UNSUPPORTED, tierOf(specs(is64Bit = false)))
    }

    @Test
    fun `시스템이 저사양이라고 표시하면 램이 넉넉해도 못 돌린다`() {
        assertEquals(ModelTier.UNSUPPORTED, tierOf(specs(ramGb = 12.0, lowRam = true)))
    }

    @Test
    fun `램이 최소선 아래면 못 돌린다`() {
        assertEquals(ModelTier.UNSUPPORTED, tierOf(specs(ramGb = 3.5)))
    }

    // --- 두 축을 같이 보는 이유 ---

    @Test
    fun `램이 넉넉해도 약한 칩이면 제일 작은 모델만 준다`() {
        // 숫자로는 다 통과하는데 실제로는 답이 견디기 힘들게 느렸던 그 경우.
        assertEquals(ModelTier.LITE, tierOf(specs(ramGb = 8.0, cores = 8, chip = ChipClass.LOW)))
    }

    @Test
    fun `좋은 칩이어도 램이 모자라면 큰 모델을 안 준다`() {
        assertEquals(ModelTier.STANDARD, tierOf(specs(ramGb = 6.0, chip = ChipClass.HIGH)))
    }

    // --- 모르는 칩 ---

    @Test
    fun `모르는 칩은 램과 코어가 넉넉하면 중간까지 간다`() {
        assertEquals(ModelTier.STANDARD, tierOf(specs(ramGb = 8.0, cores = 8, chip = ChipClass.UNKNOWN)))
    }

    @Test
    fun `모르는 칩은 램이 아무리 많아도 제일 큰 모델로는 못 간다`() {
        // 높게 잡는 실수가 훨씬 비싸다 - GB 를 다 받은 뒤에 느리거나 죽는다.
        assertEquals(ModelTier.STANDARD, tierOf(specs(ramGb = 16.0, cores = 8, chip = ChipClass.UNKNOWN)))
    }

    // --- 코어 수를 언제 보나 ---

    @Test
    fun `아는 플래그십은 코어가 적어도 중간 모델을 준다`() {
        // 코어 개수는 성능을 잘 대변하지 못한다. 칩을 알아봤으면 더 나은 근거가 이미 있다.
        assertEquals(ModelTier.STANDARD, tierOf(specs(ramGb = 6.0, cores = 4, chip = ChipClass.HIGH)))
    }

    @Test
    fun `모르는 칩은 코어가 모자라면 떨어진다`() {
        assertEquals(ModelTier.LITE, tierOf(specs(ramGb = 8.0, cores = 4, chip = ChipClass.UNKNOWN)))
    }

    @Test
    fun `아는 플래그십에 램과 코어가 다 넉넉하면 제일 큰 모델을 준다`() {
        assertEquals(ModelTier.PRO, tierOf(specs(ramGb = 8.0, cores = 8, chip = ChipClass.HIGH)))
    }

    // --- 경계값을 바꿔 쓸 수 있어야 한다 ---

    @Test
    fun `경계값을 올리면 같은 기기가 떨어진다`() {
        // 앱에 있을 때는 상수였다. 다른 모델을 쓰는 사람에게는 이 숫자가 그대로 맞지 않는다.
        val device = specs(ramGb = 8.0, cores = 8, chip = ChipClass.HIGH)

        assertEquals(ModelTier.PRO, tierOf(device))
        assertEquals(ModelTier.STANDARD, tierOf(device, TierThresholds(ramProGb = 12.0)))
    }
}

class ChipClassifierTest {

    @Test
    fun `아는 플래그십을 알아본다`() {
        assertEquals(ChipClass.HIGH, classifyChip("SM8650"))
        assertEquals(ChipClass.HIGH, classifyChip("Tensor G3"))
    }

    @Test
    fun `모르는 이름은 UNKNOWN 이다`() {
        // 안전한 쪽으로 떨어지는지가 중요하다. 표가 맞아서 되는 게 아니다.
        assertEquals(ChipClass.UNKNOWN, classifyChip("some-future-chip-2030"))
    }

    @Test
    fun `빈 값이나 null 만 와도 터지지 않는다`() {
        assertEquals(ChipClass.UNKNOWN, classifyChip(null, "", null))
    }

    @Test
    fun `여러 후보 중 하나만 맞아도 알아본다`() {
        // 안드로이드에서는 SOC_MODEL 이 비고 HARDWARE 에만 값이 있는 기기가 흔하다.
        assertEquals(ChipClass.HIGH, classifyChip(null, "exynos2400", ""))
    }
}
