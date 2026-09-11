package io.github.wesleyproduct.llmkit.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SamplerLadderTest {
    private val base = Sampling()
    private val stride = GenerationLimits().seedStride

    @Test
    fun `the first attempt is the base, seed left to the runtime`() {
        val s = samplerFor(0, base, stride)
        assertEquals(base, s)
        assertNull(s.seed)
    }

    @Test
    fun `negative attempts behave like the first`() {
        assertEquals(base, samplerFor(-3, base, stride))
    }

    @Test
    fun `retries move the seed by one stride each`() {
        assertEquals(stride, samplerFor(1, base, stride).seed)
        assertEquals(2 * stride, samplerFor(2, base, stride).seed)
    }

    @Test
    fun `retries keep every other knob`() {
        val s = samplerFor(2, base, stride)
        assertEquals(base.topK, s.topK)
        assertEquals(base.topP, s.topP)
        assertEquals(base.temperature, s.temperature)
    }

    @Test
    fun `successive retries never share a seed`() {
        val seeds = (1..5).map { samplerFor(it, base, stride).seed }
        assertEquals(seeds.size, seeds.toSet().size)
        assertNotEquals(seeds[0], seeds[1])
    }

    @Test
    fun `defaults match the values StepGrid ships`() {
        // Guard, not a preference: the blog quotes these numbers. Change them on purpose or not at all.
        val d = GenerationLimits()
        assertEquals(2048, d.maxContextTokens)
        assertEquals(4096, d.roomyContextTokens)
        assertEquals(1000, d.maxGenChars)
        assertEquals(7919, d.seedStride)
        assertEquals(Sampling(topK = 16, topP = 0.7, temperature = 0.7, seed = null), d.sampling)
    }
}
