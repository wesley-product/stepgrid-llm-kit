package io.github.wesleyproduct.llmkit.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ContextUsageTest {

    @Test
    fun `characters are always exact`() {
        val t = ContextUsageTracker(maxTokens = 4096, counter = null)
        t.addInput("system prompt")        // 13
        t.addInput("hello")                // 5
        t.addOutput("hi there")            // 8
        val u = t.snapshot()
        assertEquals(18, u.charsIn)
        assertEquals(8, u.charsOut)
        assertEquals(1, u.turns)
        assertEquals(4096, u.maxTokens)
    }

    @Test
    fun `without a tokenizer, token counts are null — never a guess`() {
        val t = ContextUsageTracker(maxTokens = 2048, counter = null)
        t.addInput("긴 한국어 문장이 들어옵니다")
        t.addOutput("답")
        val u = t.snapshot()
        assertNull(u.tokensIn)
        assertNull(u.tokensOut)
        assertNull(u.tokens)
        assertNull(u.fractionUsed)
    }

    @Test
    fun `with a tokenizer, tokens and the fraction of the window follow it`() {
        // A stand-in tokenizer: one token per word.
        val words = TokenCounter { it.split(' ').filter { w -> w.isNotBlank() }.size }
        val t = ContextUsageTracker(maxTokens = 100, counter = words)
        t.addInput("you are a helpful walker")   // 5
        t.addInput("how far today")              // 3
        t.addOutput("about ten thousand steps")  // 4
        val u = t.snapshot()
        assertEquals(8, u.tokensIn)
        assertEquals(4, u.tokensOut)
        assertEquals(12, u.tokens)
        assertEquals(0.12, u.fractionUsed!!, 0.000001)
    }

    @Test
    fun `no cap means no fraction, even with tokens`() {
        val t = ContextUsageTracker(maxTokens = null, counter = { 7 })
        t.addInput("x")
        t.addOutput("y")
        assertEquals(14, t.snapshot().tokens)
        assertNull(t.snapshot().fractionUsed)
    }

    @Test
    fun `turns count replies, not inputs`() {
        val t = ContextUsageTracker(null, null)
        t.addInput("sys"); t.addInput("q1"); t.addOutput("a1"); t.addInput("q2"); t.addOutput("a2")
        assertEquals(2, t.snapshot().turns)
    }
}
