package io.github.wesleyproduct.llmkit.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StreamAccumulatorTest {

    @Test
    fun `delta mode appends every chunk`() {
        val acc = StreamAccumulator(StreamMode.DELTA)
        assertEquals("Hel", acc.push("Hel"))
        assertEquals("Hello", acc.push("lo"))
        assertEquals("Hello world", acc.push(" world"))
    }

    @Test
    fun `a repeated token is kept, not mistaken for a snapshot`() {
        // The bug this mode exists to prevent. Nothing about the strings can tell a repeated
        // token apart from a snapshot equal to what came before; only the contract can.
        val acc = StreamAccumulator(StreamMode.DELTA)
        acc.push("하")
        assertEquals("하하", acc.push("하"))
        assertEquals("하하하", acc.push("하"))
    }

    @Test
    fun `a delta that starts with the accumulated text is still a delta`() {
        val acc = StreamAccumulator(StreamMode.DELTA)
        acc.push("ab")
        assertEquals("abab", acc.push("ab"))
    }

    @Test
    fun `cumulative mode replaces`() {
        val acc = StreamAccumulator(StreamMode.CUMULATIVE)
        acc.push("Hel")
        acc.push("Hello")
        assertEquals("Hello world", acc.push("Hello world"))
    }

    @Test
    fun `chunks are kept untrimmed so words do not glue together`() {
        val acc = StreamAccumulator(StreamMode.DELTA)
        acc.push("one")
        assertEquals("one two", acc.push(" two"))
    }

    @Test
    fun `an empty delta changes nothing`() {
        val acc = StreamAccumulator(StreamMode.DELTA)
        acc.push("abc")
        assertEquals("abc", acc.push(""))
    }

    @Test
    fun `the default matches the runtime this library wraps`() {
        val acc = StreamAccumulator()
        acc.push("가")
        assertEquals("가가", acc.push("가"))
    }
}
