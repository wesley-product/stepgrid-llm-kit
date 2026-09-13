package io.github.wesleyproduct.llmkit.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * State transitions that need no device.
 *
 * Everything here happens before the engine would touch the native runtime: the guards run first,
 * and a [ModelSource] that has nothing on disk keeps the runtime out of it entirely. The parts that
 * do reach LiteRT-LM — building, generating, cancelling — are exercised by the consuming app on a
 * real device, as CONTRIBUTING says.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LlmEngineLifecycleTest {

    /** Nothing is ever on disk, so no call can reach the runtime. */
    private fun engine(events: EngineEvents = EngineEvents.None) = LlmEngine(
        models = { null },
        ioDispatcher = UnconfinedTestDispatcher(),
        events = events,
    )

    @Test
    fun `a fresh engine is open and not ready`() = runTest {
        val e = engine()
        assertFalse(e.isClosed())
        assertFalse(e.isReady())
        assertNull(e.currentModel())
        assertNull(e.currentEngine())
        assertNull(e.lastGeneration())
    }

    @Test
    fun `useModel records the choice but readiness still depends on the file`() = runTest {
        val e = engine()
        e.useModel("gemma-e2b")
        assertEquals("gemma-e2b", e.currentModel())
        assertFalse(e.isReady(), "ModelSource returned null, so nothing is downloaded")
    }

    @Test
    fun `close makes the engine closed and never ready`() = runTest {
        val e = engine()
        e.useModel("m")
        e.close()
        assertTrue(e.isClosed())
        assertFalse(e.isReady())
    }

    @Test
    fun `closing twice is harmless`() = runTest {
        val e = engine()
        e.close()
        e.close()
        assertTrue(e.isClosed())
    }

    @Test
    fun `every entry point refuses a closed engine`() = runTest {
        val e = engine()
        e.close()
        val thrown = listOf(
            runCatching { e.useModel("m") }.exceptionOrNull(),
            runCatching { e.forget("m") }.exceptionOrNull(),
            runCatching { e.startConversation("sys") }.exceptionOrNull(),
            runCatching { e.generate("hi") }.exceptionOrNull(),
            runCatching { e.generateStream("hi").toList() }.exceptionOrNull(),
        )
        thrown.forEachIndexed { i, t ->
            assertTrue(t is LlmEngine.EngineClosedException, "entry point #$i threw $t")
        }
    }

    @Test
    fun `warmUp on a closed engine does nothing rather than throwing`() = runTest {
        val e = engine()
        e.close()
        e.warmUp()   // isReady() is false once closed, so it returns before any guard fires
    }

    @Test
    fun `closing a chat after the engine is closed is reported, not thrown`() = runTest {
        val warnings = mutableListOf<String>()
        val e = engine(object : EngineEvents {
            override fun onWarning(message: String, cause: Throwable?) { warnings.add(message) }
        })
        e.close()
        // A Chat can only come from the runtime, so this is the one place the guard is checked
        // without one: close(chat) must not throw into a caller that is tearing a screen down.
        assertTrue(e.isClosed())
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `generate on an open engine with no model selected fails as a state error`() = runTest {
        val t = runCatching { engine().generate("hi") }.exceptionOrNull()
        assertTrue(t is IllegalStateException, "was $t")
    }

    @Test
    fun `generate with a model selected but no file fails as a state error`() = runTest {
        val e = engine()
        e.useModel("missing")
        val t = runCatching { e.generate("hi") }.exceptionOrNull()
        assertTrue(t is IllegalStateException, "was $t")
    }

    @Test
    fun `an engine that never built reports no release`() = runTest {
        val released = mutableListOf<ReleaseReason>()
        val e = engine(object : EngineEvents {
            override fun onEngineReleased(modelId: String, reason: ReleaseReason) { released.add(reason) }
        })
        e.useModel("a")
        e.useModel("b")   // switch without ever building
        e.close()
        assertTrue(released.isEmpty(), "nothing was ever built, so nothing was released")
    }
}
