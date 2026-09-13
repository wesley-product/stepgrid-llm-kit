package io.github.wesleyproduct.llmkit.devicetier

/**
 * The device facts a tier decision is made from. **Values only.**
 *
 * This type exists to separate reading Android from deciding on what was read. Glued together, a
 * mid-range device with 6 GB RAM and 8 cores cannot be constructed in a test, so the decision
 * cannot be tested — which is exactly why it went untested for a long time.
 *
 * On Android use [readDeviceSpecs]; anywhere else, build one directly.
 */
public data class DeviceSpecs(
    /** Total RAM — what the device has, not what the app may use. */
    val ramGb: Double,
    /** Cores available right now. Can be fewer than physical cores in a power-saving state. */
    val cores: Int,
    val is64Bit: Boolean,
    /** The system flagged this device as low-RAM. */
    val lowRam: Boolean,
    val chip: ChipClass,
    /**
     * The chip name as detected, verbatim. Not used by [tierOf] (which reads [chip] only), but
     * needed when showing the user or attaching to a report — without it, a device that fell into
     * "unknown chip" can never be identified after the fact.
     */
    val soc: String = "",
)

/**
 * Chip class. Android has **no API that says how fast a chip is**; the only option is matching the
 * name string against known patterns ([classifyChip]).
 *
 * So [UNKNOWN] is not an exception — it is common and normal, and becomes more common the day a
 * new chip ships. How unknown chips are handled is the most important decision in this module;
 * see [tierOf].
 */
public enum class ChipClass { HIGH, MID, LOW, UNKNOWN }
