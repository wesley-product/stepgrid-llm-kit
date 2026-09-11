package io.github.wesleyproduct.llmkit.devicetier

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * 기기에서 [DeviceSpecs] 를 읽는다. **이 파일만 안드로이드에 붙어 있다.**
 *
 * 분기가 거의 없다 — 읽어서 담기만 한다. 판단은 [tierOf] 에 있고 그쪽은 순수 함수라
 * 로보렉트릭 없이 평범한 유닛 테스트로 검사할 수 있다. 둘을 한 함수에 두면 램 6GB 에
 * 코어 8개인 미들급 기기를 테스트에서 만들 수 없어서 판단 전체가 검사 밖으로 나간다.
 *
 * ```
 * val tier = tierOf(readDeviceSpecs(context))
 * ```
 */
public fun readDeviceSpecs(context: Context): DeviceSpecs {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }

    return DeviceSpecs(
        ramGb = info.totalMem / (1024.0 * 1024.0 * 1024.0),
        cores = Runtime.getRuntime().availableProcessors(),
        is64Bit = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty(),
        lowRam = am.isLowRamDevice,
        chip = classifyChip(socModel(), Build.HARDWARE, Build.BOARD),
    )
}

/**
 * `Build.SOC_MODEL` 은 API 31 부터 있고, 그 아래에서는 값 자체가 없다.
 * 있어도 `unknown` 이 들어 있는 기기가 있어서 그것도 없는 것으로 친다.
 */
private fun socModel(): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val model = Build.SOC_MODEL
    return model.takeUnless { it.isNullOrBlank() || it.equals("unknown", ignoreCase = true) }
}
