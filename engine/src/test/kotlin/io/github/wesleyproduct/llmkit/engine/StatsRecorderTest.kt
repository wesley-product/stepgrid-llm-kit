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
        val s = r.finish(maxChars = 1000)
        assertEquals(850L, s.firstTokenMillis)
        assertEquals(2_850L, s.totalMillis)
    }

    @Test
    fun `chars is the final text length, emissions the chunk count`() {
        val r = StatsRecorder(attempt = 0, now = Clock())
        r.emission("a"); r.emission("ab"); r.emission("abc")
        val s = r.finish(1000)
        assertEquals(3, s.chars)
        assertEquals(3, s.emissions)
    }

    @Test
    fun `no chunks means no first-token time`() {
        val c = Clock()
        val r = StatsRecorder(attempt = 0, now = c)
        c.t += 300
        val s = r.finish(1000)
        assertNull(s.firstTokenMillis)
        assertEquals(0, s.chars)
        assertEquals(300L, s.totalMillis)
    }

    @Test
    fun `hitting the cap is reported`() {
        val r = StatsRecorder(attempt = 1, now = Clock())
        r.emission("x".repeat(1000))
        assertTrue(r.finish(maxChars = 1000).truncatedByCap)
        val r2 = StatsRecorder(attempt = 0, now = Clock())
        r2.emission("short")
        assertFalse(r2.finish(1000).truncatedByCap)
    }

    @Test
    fun `chars per second, and no division by zero`() {
        val c = Clock()
        val r = StatsRecorder(attempt = 0, now = c)
        c.t += 2_000
        r.emission("x".repeat(100))
        assertEquals(50.0, r.finish(1000).charsPerSecond, 0.0001)

        val instant = StatsRecorder(attempt = 0, now = Clock())
        instant.emission("abc")
        assertEquals(0.0, instant.finish(1000).charsPerSecond)
    }

    @Test
    fun `the attempt index is carried through`() {
        assertEquals(3, StatsRecorder(attempt = 3, now = Clock()).finish(1000).attempt)
    }
}
