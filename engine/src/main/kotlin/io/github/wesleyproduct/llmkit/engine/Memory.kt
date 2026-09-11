package io.github.wesleyproduct.llmkit.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Debug

/**
 * Process and device memory at one instant. Model weights are mapped by the native runtime, so
 * [nativeHeapAllocatedBytes] is where an engine shows up; the JVM heap barely moves.
 *
 * @property availMemBytes What the system says is still available device-wide.
 * @property lowMemory The system's own low-memory flag — when this is set, the next allocation
 *   may be the one that gets the process killed.
 */
public data class MemorySnapshot(
    val javaHeapUsedBytes: Long,
    val nativeHeapAllocatedBytes: Long,
    val availMemBytes: Long,
    val totalMemBytes: Long,
    val lowMemory: Boolean,
    val atMillis: Long,
)

/** The difference two snapshots make. Positive = more used / less available. */
public data class MemoryDelta(
    val javaHeapBytes: Long,
    val nativeHeapBytes: Long,
    val availMemConsumedBytes: Long,
    val millis: Long,
) {
    public companion object {
        public fun between(before: MemorySnapshot, after: MemorySnapshot): MemoryDelta = MemoryDelta(
            javaHeapBytes = after.javaHeapUsedBytes - before.javaHeapUsedBytes,
            nativeHeapBytes = after.nativeHeapAllocatedBytes - before.nativeHeapAllocatedBytes,
            availMemConsumedBytes = before.availMemBytes - after.availMemBytes,
            millis = after.atMillis - before.atMillis,
        )
    }
}

/** Read the current memory state. Cheap enough to call around an engine build or a generation. */
public fun readMemory(context: Context): MemorySnapshot {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    val rt = Runtime.getRuntime()
    return MemorySnapshot(
        javaHeapUsedBytes = rt.totalMemory() - rt.freeMemory(),
        nativeHeapAllocatedBytes = Debug.getNativeHeapAllocatedSize(),
        availMemBytes = info.availMem,
        totalMemBytes = info.totalMem,
        lowMemory = info.lowMemory,
        atMillis = System.currentTimeMillis(),
    )
}
