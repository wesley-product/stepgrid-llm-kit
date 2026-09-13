package io.github.wesleyproduct.llmkit.devicetier

/**
 * Which size of model this device should get.
 *
 * Read the **meaning**, not the name: which concrete model is which tier is the caller's decision.
 * With a single model, use it as a gate — anything but [UNSUPPORTED] can run it.
 */
public enum class ModelTier {
    /** Do not load any model. */
    UNSUPPORTED,

    /** The smallest model only. */
    LITE,

    /** The mid-size model. */
    STANDARD,

    /** The largest model. */
    PRO,
}

/**
 * The thresholds that separate tiers.
 *
 * The defaults were chosen for a **4B-INT4 / 1.5B / 0.6B lineup** and will not fit another model
 * set as-is. Inside the original app these were constants; they became parameters when the code
 * was extracted — fixed numbers are useless in code someone else runs.
 */
public data class TierThresholds(
    /** Below this, no model runs. */
    val ramMinGb: Double = 4.0,
    /** RAM needed for the mid-size model. */
    val ramStandardGb: Double = 6.0,
    /** RAM needed for the largest model. */
    val ramProGb: Double = 8.0,
    /** Core count used in place of a chip class when the chip is unknown. */
    val coresMin: Int = 6,
)

/**
 * Decide a tier from [DeviceSpecs]. **Pure** — no Android, no context, no clock.
 *
 * Two axes are judged together because neither is trustworthy alone. RAM alone says an 8 GB
 * budget phone beats a 6 GB flagship; the chip alone puts a large model on a good chip that lacks
 * the RAM for it.
 *
 * ### Unknown chips
 *
 * [ChipClass.UNKNOWN] can reach [ModelTier.STANDARD] on RAM and cores alone, and **never**
 * [ModelTier.PRO]. The two directions of error cost differently: guess low and the user gets a
 * lesser answer without knowing it; guess high and they download gigabytes, then it crawls or
 * crashes. The second is far more expensive, so uncertainty leans low.
 */
public fun tierOf(
    specs: DeviceSpecs,
    thresholds: TierThresholds = TierThresholds(),
): ModelTier {
    // 1. Hard gates. 32-bit, or a device the system itself calls low-RAM, ends here.
    if (!specs.is64Bit || specs.lowRam || specs.ramGb < thresholds.ramMinGb) {
        return ModelTier.UNSUPPORTED
    }

    // 2. The largest model only when certain — a known flagship with ample RAM and cores.
    if (specs.chip == ChipClass.HIGH &&
        specs.ramGb >= thresholds.ramProGb &&
        specs.cores >= thresholds.coresMin
    ) {
        return ModelTier.PRO
    }

    // 3. A weak chip never gets the mid-size model, however much RAM it has. A device with plenty
    //    of RAM and a weak chip once produced answers too slowly to bear — it passed on the numbers.
    if (specs.chip == ChipClass.LOW) return ModelTier.LITE

    // 4. Mid-size. Either route passes.
    //    The second route ignores cores because the count is a poor proxy for performance — eight
    //    cores of which four are efficiency cores really means four, and in a power-saving state the
    //    system reports only what is awake. Once the chip is recognised we already hold a better
    //    signal, so cores are consulted only for unknown chips.
    val standard = (specs.ramGb >= thresholds.ramStandardGb && specs.cores >= thresholds.coresMin) ||
        (specs.chip == ChipClass.HIGH && specs.ramGb >= thresholds.ramStandardGb)

    return if (standard) ModelTier.STANDARD else ModelTier.LITE
}

/**
 * Map a chip name to a class.
 *
 * **This table goes stale.** A new chip lands in [ChipClass.UNKNOWN] from the day it ships. What
 * keeps that safe is [tierOf] leaning low on unknowns — not the table being right. Adapt it to the
 * devices you actually see; [DeviceSpecs.soc] tells you what those were.
 *
 * @param identifiers Strings that might name the chip. On Android, pass `Build.SOC_MODEL`,
 *   `Build.HARDWARE` and `Build.BOARD` together — any one of them alone is often blank.
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
