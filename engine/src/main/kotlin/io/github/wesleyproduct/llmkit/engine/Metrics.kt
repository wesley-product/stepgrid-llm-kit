package io.github.wesleyproduct.llmkit.engine

/**
 * What the engine actually ended up with after walking the fallback ladder. Everything here is a
 * fact the runtime confirmed — not what we asked for.
 *
 * @property rungIndex Position in [engineLadder] that succeeded (0 = the preferred configuration).
 * @property attemptsFailed Rungs that threw before this one worked.
 * @property buildMillis Wall time from first attempt to a live engine — the cold start.
 * @property memoryBefore / [memoryAfter] Snapshots around the build when a probe was supplied,
 *   so the engine's own footprint can be read as a delta. `null` when not probed.
 */
public data class EngineInfo(
    val modelId: String,
    val modelPath: String,
    val compute: Compute,
    val maxTokens: Int?,
    val cached: Boolean,
    val rungIndex: Int,
    val attemptsFailed: Int,
    val buildMillis: Long,
    val memoryBefore: MemorySnapshot? = null,
    val memoryAfter: MemorySnapshot? = null,
) {
    /** How much the build cost in memory, when both snapshots exist. */
    val memoryDelta: MemoryDelta? get() =
        if (memoryBefore != null && memoryAfter != null) MemoryDelta.between(memoryBefore, memoryAfter) else null
}

/**
 * One generation, measured. Produced for every [LlmEngine.generate], [LlmEngine.generateStream],
 * [LlmEngine.send] and [LlmEngine.sendStream]. Counts what the caller actually received: a chunk
 * that crossed the output cap is neither emitted nor counted.
 *
 * @property firstTokenMillis Time until the first chunk arrived — what the user feels as latency.
 *   `null` if nothing was emitted.
 * @property chars Length of the final delivered text.
 * @property emissions How many chunks were delivered (1 for non-streamed calls).
 * @property truncatedByCap Whether the client-side character cap stopped the stream.
 */
public data class GenerationStats(
    val attempt: Int,
    val firstTokenMillis: Long?,
    val totalMillis: Long,
    val chars: Int,
    val emissions: Int,
    val truncatedByCap: Boolean,
) {
    val charsPerSecond: Double get() = if (totalMillis <= 0) 0.0 else chars * 1000.0 / totalMillis
}

/**
 * Records a generation as it happens. Pure: the clock is injected so it can be tested without
 * waiting. One recorder per generation; [finish] is called once, by the engine.
 */
internal class StatsRecorder(
    val attempt: Int,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val startedAt = now()
    private var firstAt: Long? = null
    private var emissions = 0
    private var chars = 0

    /** Call with the accumulated text after each *delivered* chunk. */
    fun emission(textSoFar: String) {
        if (firstAt == null) firstAt = now()
        emissions++
        chars = textSoFar.length
    }

    fun finish(truncatedByCap: Boolean): GenerationStats = GenerationStats(
        attempt = attempt,
        firstTokenMillis = firstAt?.let { it - startedAt },
        totalMillis = now() - startedAt,
        chars = chars,
        emissions = emissions,
        truncatedByCap = truncatedByCap,
    )
}

/**
 * Counts tokens the way the model's tokenizer would. Supply one if you have it; the engine never
 * guesses — characters are not tokens, and a chars-per-token ratio that holds for English is wrong
 * for Korean by a factor that depends on the text.
 */
public fun interface TokenCounter {
    /** Number of tokens [text] occupies in the model's context window. */
    public fun count(text: String): Int
}

/**
 * How much of a conversation's context window has been used — as far as we can know.
 *
 * [charsIn] / [charsOut] are exact. [tokensIn] / [tokensOut] exist only when a [TokenCounter] was
 * given to the engine; otherwise they are `null`, deliberately, rather than an estimate.
 * [maxTokens] is the cap the live engine was built with (`null` if the runtime chose).
 */
public data class ContextUsage(
    val turns: Int,
    val charsIn: Int,
    val charsOut: Int,
    val tokensIn: Int?,
    val tokensOut: Int?,
    val maxTokens: Int?,
) {
    val tokens: Int? get() = if (tokensIn != null && tokensOut != null) tokensIn + tokensOut else null

    /** Fraction of the window used, when both a token count and a cap are known. */
    val fractionUsed: Double? get() {
        val t = tokens ?: return null
        val cap = maxTokens ?: return null
        return if (cap <= 0) null else t.toDouble() / cap
    }
}

/**
 * Mutable side of [ContextUsage]; one per chat. Writes happen on the engine's dispatcher under its
 * mutex; [snapshot] may be called from any thread, so all three are synchronized — a snapshot is
 * never a mix of two turns.
 */
internal class ContextUsageTracker(
    private val maxTokens: Int?,
    private val counter: TokenCounter?,
) {
    private var turns = 0
    private var charsIn = 0
    private var charsOut = 0
    private var tokensIn = 0
    private var tokensOut = 0

    @Synchronized
    fun addInput(text: String) {
        charsIn += text.length
        if (counter != null) tokensIn += counter.count(text)
    }

    @Synchronized
    fun addOutput(text: String) {
        turns++
        charsOut += text.length
        if (counter != null) tokensOut += counter.count(text)
    }

    @Synchronized
    fun snapshot(): ContextUsage = ContextUsage(
        turns = turns,
        charsIn = charsIn,
        charsOut = charsOut,
        tokensIn = if (counter != null) tokensIn else null,
        tokensOut = if (counter != null) tokensOut else null,
        maxTokens = maxTokens,
    )
}

/** Why an engine was torn down. */
public enum class ReleaseReason { MODEL_SWITCHED, MODEL_FORGOTTEN, CLOSED }

/**
 * Callbacks for anyone who wants to watch the engine — telemetry, a debug overlay, logs. All
 * methods default to nothing, so implement only what you need.
 *
 * Every callback is invoked on the engine's dispatcher (the `ioDispatcher` given to [LlmEngine]),
 * including [onGeneration] after a streamed reply. Hop to the main thread yourself if you touch UI.
 */
public interface EngineEvents {
    /** An engine came up. [info] says on which rung, how long it took and what it cost. */
    public fun onEngineBuilt(info: EngineInfo) {}

    /** One rung of the ladder failed and the next will be tried. [index] is 0-based out of [total].
     *  If every rung fails, the calling operation throws the last [cause] instead. */
    public fun onEngineBuildAttemptFailed(attempt: EngineAttempt, index: Int, total: Int, cause: Throwable) {}

    /** The live engine was torn down — because the model changed, was deleted, or the engine was closed. */
    public fun onEngineReleased(modelId: String, reason: ReleaseReason) {}

    /** A generation finished (normally or by cancellation). Not called for calls that failed before producing anything. */
    public fun onGeneration(stats: GenerationStats) {}

    /** Something recoverable went wrong: a fallback rung, a failed warm-up, a failed chat close. */
    public fun onWarning(message: String, cause: Throwable?) {}

    public companion object {
        /** Silence. A library should not write to the host's log unasked. */
        public val None: EngineEvents = object : EngineEvents {}
    }
}
