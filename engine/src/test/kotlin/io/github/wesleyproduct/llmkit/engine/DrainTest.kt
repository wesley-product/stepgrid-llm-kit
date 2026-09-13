package io.github.wesleyproduct.llmkit.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The one path a device cannot show us: what happens when the runtime's stream dies the instant it
 * is told to stop.
 *
 * LiteRT-LM ends `sendMessageAsync` as a `CancellationException` when `cancelProcess()` takes
 * effect. If that escapes `drain`, the outcome never reaches the caller — the reply looks like a
 * failure instead of a capped one, no stats are published, and a `Chat` goes on reporting itself
 * usable with half an assistant turn already in its history. These tests exist so that cannot come
 * back quietly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DrainTest {

    private fun engine(events: EngineEvents = EngineEvents.None, limits: GenerationLimits = GenerationLimits()) =
        LlmEngine(
            models = { null },
            limits = limits,
            ioDispatcher = UnconfinedTestDispatcher(),
            events = events,
        )

    /** Emits [chunks], then ends the way the runtime does once it has been asked to stop. */
    private fun stoppedAfter(vararg chunks: String, ending: Throwable): Flow<String> = flow {
        chunks.forEach { emit(it) }
        throw ending
    }

    @Test
    fun `a stream that dies right after the cap reports the cap, not a failure`() = runTest {
        var stops = 0
        val seen = mutableListOf<String>()
        val outcome = engine().drain(
            source = stoppedAfter("12345", "67890", ending = CancellationException("cancelProcess")),
            maxChars = 8,
            stop = { stops++ },
        ) { seen.add(it) }

        assertTrue(outcome.capped, "the cap tripped, so the outcome must say so")
        assertNull(outcome.downstream, "nothing downstream failed")
        assertTrue(outcome.stoppedEarly)
        assertEquals(1, stops, "the runtime is asked to stop exactly once")
        assertEquals(listOf("12345"), seen, "the chunk that crossed the cap is not delivered")
    }

    @Test
    fun `a stream that dies right after the collector goes away keeps the collector's failure`() = runTest {
        val gone = CancellationException("collector gone")
        var stops = 0
        val outcome = engine().drain(
            source = stoppedAfter("a", "b", ending = CancellationException("cancelProcess")),
            maxChars = 1_000,
            stop = { stops++ },
        ) { throw gone }

        assertSame(gone, outcome.downstream, "the caller's own cancellation is what gets rethrown")
        assertFalse(outcome.capped)
        assertTrue(outcome.stoppedEarly)
        assertEquals(1, stops)
    }

    @Test
    fun `a failure we did not ask for still propagates`() = runTest {
        val boom = IllegalStateException("native runtime fell over")
        var stops = 0
        val t = runCatching {
            engine().drain(
                source = stoppedAfter("a", ending = boom),
                maxChars = 1_000,
                stop = { stops++ },
            ) { }
        }.exceptionOrNull()

        // Identity, not equality: kotlinx.coroutines copies a thrown exception to graft a recovered
        // stack trace onto it, so the instance that comes out is not the one that went in.
        assertTrue(t is IllegalStateException, "nobody asked the runtime to stop, so this is a real failure: was $t")
        assertEquals(boom.message, t?.message)
        assertEquals(0, stops)
    }

    @Test
    fun `an Error is never swallowed, even after we asked the runtime to stop`() = runTest {
        val fatal = OutOfMemoryError("native heap")
        val t = runCatching {
            engine().drain(
                source = stoppedAfter("1234567890", ending = fatal),
                maxChars = 4,
                stop = { },
            ) { }
        }.exceptionOrNull()

        assertTrue(t is OutOfMemoryError, "was $t")
        assertEquals(fatal.message, t?.message)
    }

    @Test
    fun `the abrupt ending is reported as a warning rather than lost`() = runTest {
        val warnings = mutableListOf<String>()
        val events = object : EngineEvents {
            override fun onWarning(message: String, cause: Throwable?) { warnings.add(message) }
        }
        engine(events).drain(
            source = stoppedAfter("1234567890", ending = CancellationException("cancelProcess")),
            maxChars = 4,
            stop = { },
        ) { }

        assertEquals(1, warnings.size, "swallowing it silently would hide a runtime change: $warnings")
    }

    @Test
    fun `a stream that ends on its own is not stopped and not capped`() = runTest {
        var stops = 0
        val seen = mutableListOf<String>()
        val outcome = engine().drain(
            source = flowOf("Hel", "lo"),
            maxChars = 1_000,
            stop = { stops++ },
        ) { seen.add(it) }

        assertFalse(outcome.stoppedEarly)
        assertEquals(0, stops, "a runtime that finished needs no cancelProcess")
        assertEquals(listOf("Hel", "Hello"), seen, "each emission is the text so far")
    }

    @Test
    fun `chunks after the cap are read but never delivered`() = runTest {
        val seen = mutableListOf<String>()
        val outcome = engine().drain(
            source = flowOf("ab", "cd", "ef", "gh"),
            maxChars = 4,
            stop = { },
        ) { seen.add(it) }

        assertTrue(outcome.capped)
        assertEquals(listOf("ab"), seen, "draining means reading the rest, not delivering it")
    }

    @Test
    fun `the configured stream mode is what accumulates`() = runTest {
        val seen = mutableListOf<String>()
        engine(limits = GenerationLimits(streamMode = StreamMode.CUMULATIVE)).drain(
            source = flowOf("Hel", "Hello"),
            maxChars = 1_000,
            stop = { },
        ) { seen.add(it) }

        assertEquals(listOf("Hel", "Hello"), seen, "cumulative chunks replace, they do not append")
    }
}
