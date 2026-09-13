package io.github.wesleyproduct.llmkit.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StatsRecorderTest {
    /** A clock we advance by hand, so timing assertions are exact. */
    private class Clock(var t: Long = 1_000) : () -> Long {
        override fun invoke(): Long = t
    }

    @Test
    fun `first token latency is measured from start to the first chunk`() {
        val c = Clock()
        val r = StatsRecorder(attempt = 0, now = c)
        c.t += 850
        r.emission("안")
        c.t += 2_000
        r.emission("안녕하세요")
        val s = r.finish(truncatedByCap = false)
        assertEquals(850L, s.firstTokenMillis)
        assertEquals(2_850L, s.totalMillis)
    }

    @Test
    fun `chars is the final text length, emissions the chunk count`() {
        val r = StatsRecorder(attempt = 0, now = Clock())
        r.emission("a"); r.emission("ab"); r.emission("abc")
        val s = r.finish(truncatedByCap = false)
        assertEquals(3, s.chars)
        assertEquals(3, s.emissions)
    }

    @Test
    fun `no chunks means no first-token time`() {
        val c = Clock()
        val r = StatsRecorder(attempt = 0, now = c)
        c.t += 300
        val s = r.finish(truncatedByCap = false)
        assertNull(s.firstTokenMillis)
        assertEquals(0, s.chars)
        assertEquals(300L, s.totalMillis)
    }

    @Test
    fun `the cap flag is what the engine observed, not inferred from length`() {
        // The engine decides truncation when it drops the chunk that crossed the cap; the recorder
        // only ever sees delivered chunks, so it must not re-derive the flag from chars.
        val r = StatsRecorder(attempt = 1, now = Clock())
        r.emission("x".repeat(990))
        assertTrue(r.finish(truncatedByCap = true).truncatedByCap)
        val r2 = StatsRecorder(attempt = 0, now = Clock())
        r2.emission("x".repeat(990))
        assertFalse(r2.finish(truncatedByCap = false).truncatedByCap)
    }

    @Test
    fun `chars per second, and no division by zero`() {
        val c = Clock()
        val r = StatsRecorder(attempt = 0, now = c)
        c.t += 2_000
        r.emission("x".repeat(100))
        assertEquals(50.0, r.finish(false).charsPerSecond, 0.0001)

        val instant = StatsRecorder(attempt = 0, now = Clock())
        instant.emission("abc")
        assertEquals(0.0, instant.finish(false).charsPerSecond)
    }

    @Test
    fun `the attempt index is carried through`() {
        assertEquals(3, StatsRecorder(attempt = 3, now = Clock()).finish(false).attempt)
    }
}
