package io.github.wesleyproduct.llmkit.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MemoryDeltaTest {
    private val gb = 1L shl 30

    private fun snap(javaMb: Long, nativeMb: Long, availGb: Long, at: Long) = MemorySnapshot(
        javaHeapUsedBytes = javaMb shl 20,
        nativeHeapAllocatedBytes = nativeMb shl 20,
        availMemBytes = availGb * gb,
        totalMemBytes = 12 * gb,
        lowMemory = false,
        atMillis = at,
    )

    @Test
    fun `an engine build shows up as native heap and lost available memory`() {
        val before = snap(javaMb = 40, nativeMb = 120, availGb = 6, at = 0)
        val after = snap(javaMb = 42, nativeMb = 2_600, availGb = 3, at = 4_200)
        val d = MemoryDelta.between(before, after)
        assertEquals(2L shl 20, d.javaHeapBytes)
        assertEquals(2_480L shl 20, d.nativeHeapBytes)
        assertEquals(3 * gb, d.availMemConsumedBytes)
        assertEquals(4_200L, d.millis)
    }

    @Test
    fun `freeing memory gives negative deltas rather than clamping`() {
        val d = MemoryDelta.between(snap(50, 3_000, 2, 0), snap(48, 200, 5, 100))
        assertEquals(-(2L shl 20), d.javaHeapBytes)
        assertEquals(-(2_800L shl 20), d.nativeHeapBytes)
        assertEquals(-3 * gb, d.availMemConsumedBytes)
    }

    @Test
    fun `engine info exposes the delta only when both snapshots exist`() {
        val base = EngineInfo("m", "/p", Compute.GPU, 4096, cached = true, rungIndex = 0, attemptsFailed = 0, buildMillis = 3_000)
        assertNull(base.memoryDelta)
        val probed = base.copy(memoryBefore = snap(40, 100, 6, 0), memoryAfter = snap(40, 2_500, 4, 3_000))
        assertEquals(2_400L shl 20, probed.memoryDelta!!.nativeHeapBytes)
    }
}
