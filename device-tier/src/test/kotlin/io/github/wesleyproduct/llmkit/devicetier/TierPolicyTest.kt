package io.github.wesleyproduct.llmkit.devicetier

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * This file is half the reason the module exists.
 *
 * While the decision was glued to the Android lookup in one function, these tests **could not be
 * written**: a mid-range device with 6 GB RAM and 8 cores would have required faking `Build` and
 * `ActivityManager`, and then the thing under test is the fake, not the decision.
 */
class TierPolicyTest {

    private fun specs(
        ramGb: Double = 8.0,
        cores: Int = 8,
        is64Bit: Boolean = true,
        lowRam: Boolean = false,
        chip: ChipClass = ChipClass.HIGH,
    ) = DeviceSpecs(ramGb = ramGb, cores = cores, is64Bit = is64Bit, lowRam = lowRam, chip = chip)

    // --- hard gates ---

    @Test
    fun `a 32-bit device runs nothing`() {
        assertEquals(ModelTier.UNSUPPORTED, tierOf(specs(is64Bit = false)))
    }

    @Test
    fun `a device the system flags as low-RAM runs nothing, however much RAM it reports`() {
        assertEquals(ModelTier.UNSUPPORTED, tierOf(specs(ramGb = 12.0, lowRam = true)))
    }

    @Test
    fun `below the RAM floor runs nothing`() {
        assertEquals(ModelTier.UNSUPPORTED, tierOf(specs(ramGb = 3.5)))
    }

    // --- why two axes ---

    @Test
    fun `plenty of RAM with a weak chip still gets only the smallest model`() {
        // Passed on every number; produced answers too slowly to bear.
        assertEquals(ModelTier.LITE, tierOf(specs(ramGb = 8.0, cores = 8, chip = ChipClass.LOW)))
    }

    @Test
    fun `a good chip without the RAM does not get the largest model`() {
        assertEquals(ModelTier.STANDARD, tierOf(specs(ramGb = 6.0, chip = ChipClass.HIGH)))
    }

    // --- unknown chips ---

    @Test
    fun `an unknown chip reaches mid-size on ample RAM and cores`() {
        assertEquals(ModelTier.STANDARD, tierOf(specs(ramGb = 8.0, cores = 8, chip = ChipClass.UNKNOWN)))
    }

    @Test
    fun `an unknown chip never reaches the largest model, however much RAM`() {
        // Guessing high costs far more: gigabytes downloaded, then it crawls or crashes.
        assertEquals(ModelTier.STANDARD, tierOf(specs(ramGb = 16.0, cores = 8, chip = ChipClass.UNKNOWN)))
    }

    // --- when cores matter ---

    @Test
    fun `a known flagship gets mid-size even with few cores`() {
        // Core count is a poor proxy; a recognised chip is already the better signal.
        assertEquals(ModelTier.STANDARD, tierOf(specs(ramGb = 6.0, cores = 4, chip = ChipClass.HIGH)))
    }

    @Test
    fun `an unknown chip with too few cores drops a tier`() {
        assertEquals(ModelTier.LITE, tierOf(specs(ramGb = 8.0, cores = 4, chip = ChipClass.UNKNOWN)))
    }

    @Test
    fun `a known flagship with ample RAM and cores gets the largest model`() {
        assertEquals(ModelTier.PRO, tierOf(specs(ramGb = 8.0, cores = 8, chip = ChipClass.HIGH)))
    }

    // --- thresholds must be adjustable ---

    @Test
    fun `raising a threshold drops the same device`() {
        // These were constants inside the app; another model lineup needs other numbers.
        val device = specs(ramGb = 8.0, cores = 8, chip = ChipClass.HIGH)

        assertEquals(ModelTier.PRO, tierOf(device))
        assertEquals(ModelTier.STANDARD, tierOf(device, TierThresholds(ramProGb = 12.0)))
    }
}

class ChipClassifierTest {

    @Test
    fun `recognises known flagships`() {
        assertEquals(ChipClass.HIGH, classifyChip("SM8650"))
        assertEquals(ChipClass.HIGH, classifyChip("Tensor G3"))
    }

    @Test
    fun `an unknown name is UNKNOWN`() {
        // Falling to the safe side is what matters; the table being right is not the mechanism.
        assertEquals(ChipClass.UNKNOWN, classifyChip("some-future-chip-2030"))
    }

    @Test
    fun `blank and null inputs do not throw`() {
        assertEquals(ChipClass.UNKNOWN, classifyChip(null, "", null))
    }

    @Test
    fun `any one matching identifier is enough`() {
        // On Android SOC_MODEL is often blank while HARDWARE carries the name.
        assertEquals(ChipClass.HIGH, classifyChip(null, "exynos2400", ""))
    }
}
