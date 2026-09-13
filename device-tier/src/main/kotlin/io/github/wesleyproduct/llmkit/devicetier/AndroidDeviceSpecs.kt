package io.github.wesleyproduct.llmkit.devicetier

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * Reads [DeviceSpecs] from the device. **This is the only file in the module that touches Android.**
 *
 * There is almost no branching here — it reads and packs. The decision lives in [tierOf], which is
 * a pure function and can be unit-tested without Robolectric. Keeping both in one function meant a
 * mid-range device with 6 GB RAM and 8 cores could not be constructed in a test, so the whole
 * decision sat outside the tests.
 *
 * ```
 * val tier = tierOf(readDeviceSpecs(context))
 * ```
 */
public fun readDeviceSpecs(context: Context): DeviceSpecs {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    val soc = socModel() ?: listOf(Build.HARDWARE, Build.BOARD).firstOrNull { !it.isNullOrBlank() }

    return DeviceSpecs(
        ramGb = info.totalMem / (1024.0 * 1024.0 * 1024.0),
        cores = Runtime.getRuntime().availableProcessors(),
        is64Bit = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty(),
        lowRam = am.isLowRamDevice,
        chip = classifyChip(soc, Build.HARDWARE, Build.BOARD),
        soc = soc.orEmpty(),
    )
}

/**
 * `Build.SOC_MODEL` exists from API 31; below that there is no value at all. Some devices report
 * the literal `unknown` even when it exists, which is treated as absent too.
 */
private fun socModel(): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val model = Build.SOC_MODEL
    return model.takeUnless { it.isNullOrBlank() || it.equals("unknown", ignoreCase = true) }
}
