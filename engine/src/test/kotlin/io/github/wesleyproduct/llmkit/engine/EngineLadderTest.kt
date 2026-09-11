package io.github.wesleyproduct.llmkit.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EngineLadderTest {
    private val path = "/data/user/0/app/files/model.litertlm"

    @Test
    fun `six rungs, widest and fastest first, bare CPU last`() {
        val l = engineLadder(path, "/cache", GenerationLimits())
        assertEquals(6, l.size)
        assertEquals(
            listOf(
                Compute.GPU to 4096, Compute.CPU to 4096,
                Compute.GPU to 2048, Compute.CPU to 2048,
                Compute.GPU to null, Compute.CPU to null,
            ),
            l.map { it.compute to it.maxTokens },
        )
    }

    @Test
    fun `every rung carries the model path`() {
        assertTrue(engineLadder(path, null).all { it.modelPath == path })
    }

    @Test
    fun `a cache dir is passed to every rung`() {
        assertTrue(engineLadder(path, "/cache").all { it.cacheDir == "/cache" })
    }

    @Test
    fun `a blank cache dir means no cache`() {
        engineLadder(path, "   ").forEach { assertNull(it.cacheDir) }
        engineLadder(path, null).forEach { assertNull(it.cacheDir) }
    }

    @Test
    fun `custom limits change the two capped tiers only`() {
        val l = engineLadder(path, null, GenerationLimits(maxContextTokens = 1024, roomyContextTokens = 8192))
        assertEquals(listOf(8192, 8192, 1024, 1024, null, null), l.map { it.maxTokens })
    }
}
