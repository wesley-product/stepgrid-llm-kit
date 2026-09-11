package io.github.wesleyproduct.llmkit.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StreamAccumulatorTest {

    @Test
    fun `deltas are appended`() {
        val acc = StreamAccumulator()
        assertEquals("Hel", acc.push("Hel"))
        assertEquals("Hello", acc.push("lo"))
        assertEquals("Hello world", acc.push(" world"))
    }

    @Test
    fun `cumulative snapshots replace`() {
        val acc = StreamAccumulator()
        acc.push("Hel")
        acc.push("Hello")
        assertEquals("Hello world", acc.push("Hello world"))
    }

    @Test
    fun `a runtime may switch modes mid-stream and the text stays right`() {
        val acc = StreamAccumulator()
        acc.push("안녕")          // delta
        acc.push("안녕하세요")    // snapshot (starts with what we have)
        acc.push(", 저는")        // delta again
        assertEquals("안녕하세요, 저는", acc.text)
    }

    @Test
    fun `chunks are kept untrimmed so words do not glue together`() {
        val acc = StreamAccumulator()
        acc.push("one")
        acc.push(" two")
        assertEquals("one two", acc.text)
    }

    @Test
    fun `an empty chunk changes nothing`() {
        val acc = StreamAccumulator()
        acc.push("abc")
        assertEquals("abc", acc.push(""))
    }

    @Test
    fun `a chunk equal to the accumulated text is a snapshot, not a duplicate`() {
        val acc = StreamAccumulator()
        acc.push("same")
        assertEquals("same", acc.push("same"))
    }
}
