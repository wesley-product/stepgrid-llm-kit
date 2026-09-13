package io.github.wesleyproduct.llmkit.engine

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One shared on-device LLM engine over LiteRT-LM, serialized behind a single mutex.
 *
 * The model is large, so there is ONE [Engine], built lazily and reused. [engineMutex] guards its
 * whole lifecycle — build, use AND close — which is both what serializes generation (the engine is
 * not safe for concurrent use) and what makes [useModel] safe: no caller can hold an engine it is
 * about to call into while we tear it down.
 *
 * Model identity is an opaque [String]; [models] turns it into a file path. The engine knows nothing
 * about how models are named, chosen or downloaded.
 *
 * **Threading.** Every call that touches the runtime — including model switches and teardown — runs
 * on [ioDispatcher], never on the caller's thread. Every [EngineEvents] callback is delivered on
 * [ioDispatcher] too, including the ones that follow a streamed generation.
 *
 * What the engine *does* know, it reports: [currentEngine] says which backend and context size
 * actually came up and what that cost; every generation produces a [GenerationStats]; every [Chat]
 * tracks its [ContextUsage]. See [EngineEvents] to receive these as they happen.
 *
 * @param cacheDir Directory the runtime may cache compiled graphs in, so a 2nd+ load (and re-loads
 *   after a model switch) skip recompilation. `null`/blank = no cache.
 * @param tokenCounter If you have the model's tokenizer, supply it and [ContextUsage] gets token
 *   counts. Without it they are `null` — never estimated from characters.
 * @param memoryProbe Sampled right before and after an engine build so [EngineInfo.memoryDelta]
 *   can show the engine's footprint. Typically `{ readMemory(context) }`.
 * @param events Observer for builds, fallbacks, generations and warnings. Defaults to silence.
 */
public class LlmEngine(
    private val models: ModelSource,
    private val ioDispatcher: CoroutineDispatcher,
    private val cacheDir: String? = null,
    /** The limits this engine was built with. Exposed so callers can derive per-call overrides from
     *  them — `limits.sampling.copy(temperature = 0.0)` — instead of constructing from scratch. */
    public val limits: GenerationLimits = GenerationLimits(),
    private val tokenCounter: TokenCounter? = null,
    private val memoryProbe: (() -> MemorySnapshot)? = null,
    private val events: EngineEvents = EngineEvents.None,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val engineMutex = Mutex()

    // Touched only under engineMutex, except `current`, `info` and `lastStats`, read free as hints.
    private var engine: Engine? = null
    @Volatile private var current: String? = null
    @Volatile private var info: EngineInfo? = null
    @Volatile private var lastStats: GenerationStats? = null
    /** Bumped on every engine teardown and stamped onto each [Chat] handed out, so a conversation
     *  from a torn-down engine is detectable instead of being a dangling native pointer. */
    private var epoch = 0

    // Closing a chat isn't suspending (callers close from lifecycle teardown), so the native close
    // is handed to this scope to run under engineMutex like every other engine-touching call.
    // A failure inside must not take the process down — it is reported, not thrown.
    private val closeScope = CoroutineScope(
        ioDispatcher + SupervisorJob() +
            CoroutineExceptionHandler { _, t -> events.onWarning("closing a conversation failed", t) },
    )

    /** A live conversation, tagged with the engine generation that created it. */
    public class Chat internal constructor(
        internal val conversation: Conversation,
        internal val epoch: Int,
        internal val tracker: ContextUsageTracker,
    ) {
        /** How much of the context window this chat has consumed, as far as is known. Safe to call
         *  from any thread while a generation is running. */
        public fun usage(): ContextUsage = tracker.snapshot()
    }

    /** A [Chat] outlived the model it was opened on: its engine — and with it the conversation's
     *  native state — is gone. Recoverable: open a new conversation. */
    public class StaleModelException : IllegalStateException("the model changed; this conversation is gone")

    // ---- observability -----------------------------------------------------------------------

    /** The engine that is actually up, or `null` before the first build / after a teardown. */
    public fun currentEngine(): EngineInfo? = info

    /** Stats of the most recent finished generation, if any. */
    public fun lastGeneration(): GenerationStats? = lastStats

    // ---- lifecycle ---------------------------------------------------------------------------

    /**
     * Point the runtime at a different model. The engine caches weights, so switching means tearing
     * the old one down and letting the next call rebuild. Conversations opened on the old engine die
     * with it — [send] on one throws [StaleModelException] rather than calling into freed memory.
     */
    public suspend fun useModel(modelId: String): Unit = withContext(ioDispatcher) {
        engineMutex.withLock {
            if (current == modelId) return@withLock
            release(ReleaseReason.MODEL_SWITCHED)
            current = modelId
        }
    }

    /** Whether a model is selected and its weights are on disk. */
    public fun isReady(): Boolean = current?.let { models.pathOf(it) } != null

    /** The model currently selected (loaded or not). Read without the lock — it is only a hint. */
    public fun currentModel(): String? = current

    /**
     * A model's weights were deleted. If it is the one loaded, tear the engine down so the memory is
     * actually reclaimed — otherwise the engine would sit open on unlinked weights until the process
     * dies. No-op if some other model (or none) is loaded.
     */
    public suspend fun forget(modelId: String): Unit = withContext(ioDispatcher) {
        engineMutex.withLock {
            if (current != modelId) return@withLock
            release(ReleaseReason.MODEL_FORGOTTEN)
            current = null
        }
    }

    /**
     * Tear everything down: the engine, and the scope that closes chats. After this the instance is
     * dead — build a new one to use a model again. Call from wherever the engine's owner is destroyed.
     * A [close] of a chat issued after this does nothing (and says so through [EngineEvents.onWarning]).
     */
    public suspend fun close() {
        withContext(ioDispatcher) {
            engineMutex.withLock {
                release(ReleaseReason.CLOSED)
                current = null
            }
        }
        closeScope.cancel()
    }

    /** Under [engineMutex], on [ioDispatcher]. */
    private fun release(reason: ReleaseReason) {
        val had = engine != null
        val id = current
        engine?.close()
        engine = null
        info = null
        epoch++
        if (had && id != null) events.onEngineReleased(id, reason)
    }

    // ---- generation --------------------------------------------------------------------------

    /**
     * One prompt through a throwaway conversation (titles, summaries). Delegates to the streamed
     * path so it inherits the output cap — an un-streamed call has nothing else bounding it, and a
     * model that never emits EOS would hold the mutex forever.
     *
     * @param sampling Decoding for this call. Defaults to [GenerationLimits.sampling]. To change one
     *   knob and keep the rest of what the engine was configured with, **copy** rather than
     *   construct: `engine.limits.sampling.copy(temperature = 0.0)`. A fresh `Sampling(temperature = 0.0)`
     *   silently resets `topK`/`topP` to the class defaults, not to yours.
     * @param maxChars Output cap for this call. Defaults to [GenerationLimits.maxGenChars].
     */
    public suspend fun generate(
        prompt: String,
        attempt: Int = 0,
        sampling: Sampling = limits.sampling,
        maxChars: Int = limits.maxGenChars,
    ): String = withContext(ioDispatcher) {
        generateStream(prompt, attempt, sampling, maxChars).lastOrNull().orEmpty()
    }

    /**
     * Streamed one-shot generation. Each emission is the raw text SO FAR (see [StreamAccumulator]).
     * Holds the engine for the whole run. Cold: collect once per call. Stops — and releases the
     * engine — as soon as the text reaches [maxChars]; the chunk that crossed the cap is not emitted
     * and not counted in the stats. Stats are published on normal completion and on cancellation
     * (what was delivered is real either way); a failure publishes nothing.
     *
     * @param sampling See [generate] — prefer `limits.sampling.copy(...)` over a fresh instance.
     */
    public fun generateStream(
        prompt: String,
        attempt: Int = 0,
        sampling: Sampling = limits.sampling,
        maxChars: Int = limits.maxGenChars,
    ): Flow<String> {
        val stats = StatsRecorder(attempt, clock)
        var capped = false
        return flow {
            engineMutex.withLock {
                val sampler = samplerFor(attempt, sampling, limits.seedStride).toRuntime()
                engine().createConversation(ConversationConfig(samplerConfig = sampler)).use { conversation ->
                    val acc = StreamAccumulator()
                    conversation.sendMessageAsync(prompt).collect { emit(acc.push(rawTextOf(it))) }
                }
            }
        }
            .transformWhile { text -> if (text.length < maxChars) { emit(text); true } else { capped = true; false } }
            .onEach { stats.emission(it) }
            .onCompletion { cause -> if (cause == null || cause is CancellationException) publish(stats.finish(truncatedByCap = capped)) }
            .flowOn(ioDispatcher)   // everything above — including the stats callback — runs on the engine's dispatcher
    }

    /**
     * Open a persistent multi-turn conversation. The runtime keeps KV cache and history inside it,
     * so context carries across turns without re-sending a transcript. Caller must [close] it.
     *
     * @param sampling Decoding for the whole conversation. The runtime fixes it when the
     *   conversation is created, so it cannot change per [send]. Prefer `limits.sampling.copy(...)`.
     */
    public suspend fun startConversation(
        systemInstruction: String,
        sampling: Sampling = limits.sampling,
    ): Chat = withContext(ioDispatcher) {
        engineMutex.withLock {
            val conversation = engine().createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(systemInstruction),
                    samplerConfig = sampling.toRuntime(),
                ),
            )
            val tracker = ContextUsageTracker(info?.maxTokens, tokenCounter)
            tracker.addInput(systemInstruction)
            Chat(conversation, epoch, tracker)
        }
    }

    /**
     * Send one message on an existing chat and return the reply text, trimmed.
     * @throws StaleModelException if the model was switched since [chat] was opened.
     */
    public suspend fun send(chat: Chat, message: String): String = withContext(ioDispatcher) {
        val stats = StatsRecorder(attempt = 0, now = clock)
        engineMutex.withLock {
            if (chat.epoch != epoch) throw StaleModelException()
            chat.tracker.addInput(message)
            val reply = textOf(chat.conversation.sendMessage(message))
            chat.tracker.addOutput(reply)
            stats.emission(reply)
            publish(stats.finish(truncatedByCap = false))
            reply
        }
    }

    /**
     * Streamed reply: emits the text so far as the model generates it. Holds the engine for the
     * whole generation, like [send]. Emissions are untrimmed; trim the final text. Cold: collect once.
     * Stops at [maxChars] the same way [generateStream] does. Bookkeeping follows what actually
     * reached the conversation: a stale chat counts nothing; a runtime failure after the message was
     * sent counts the input but no output and publishes no stats; normal completion and cancellation
     * count both (the text delivered so far is in the conversation either way).
     * @throws StaleModelException if the model was switched since [chat] was opened.
     */
    public fun sendStream(chat: Chat, message: String, maxChars: Int = limits.maxGenChars): Flow<String> {
        val stats = StatsRecorder(attempt = 0, now = clock)
        var capped = false
        var finalText = ""
        var inputCounted = false
        return flow {
            engineMutex.withLock {
                if (chat.epoch != epoch) throw StaleModelException()
                chat.tracker.addInput(message)
                inputCounted = true
                val acc = StreamAccumulator()
                chat.conversation.sendMessageAsync(message).collect { emit(acc.push(rawTextOf(it))) }
            }
        }
            .transformWhile { text -> if (text.length < maxChars) { emit(text); true } else { capped = true; false } }
            .onEach { finalText = it; stats.emission(it) }
            .onCompletion { cause ->
                if (inputCounted && (cause == null || cause is CancellationException)) {
                    chat.tracker.addOutput(finalText)
                    publish(stats.finish(truncatedByCap = capped))
                }
            }
            .flowOn(ioDispatcher)
    }

    /**
     * Release a chat's native resources. `Engine` is final in the runtime AAR, so we can't see what
     * its own `close()` does to conversations it created — we assume it tears them down too, and
     * skip closing a stale chat rather than risk a double free. Worst case if that assumption is
     * wrong is a leak, which is the side to be wrong on. Failures are reported via
     * [EngineEvents.onWarning], never thrown. After [close] of the engine itself this is a no-op
     * and is reported as such.
     */
    public fun close(chat: Chat) {
        if (!closeScope.isActive) {
            events.onWarning("close(chat) after the engine was closed — nothing to do", null)
            return
        }
        closeScope.launch {
            engineMutex.withLock {
                if (chat.epoch == epoch) chat.conversation.close()
            }
        }
    }

    /**
     * Build the engine for the selected model ahead of time (no generation) so the first real call
     * doesn't pay the multi-second cold load. No-op when nothing is downloaded; cheap once built.
     * Same exception discipline as the ladder: cancellation and JVM [Error]s propagate; only the
     * runtime's own failures are reported as a warning.
     */
    public suspend fun warmUp() {
        if (!isReady()) return
        withContext(ioDispatcher) {
            try {
                engineMutex.withLock { engine() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                events.onWarning("warmUp failed", e)
            }
        }
    }

    // ---- internals ---------------------------------------------------------------------------

    private fun publish(stats: GenerationStats) {
        lastStats = stats
        events.onGeneration(stats)
    }

    private fun textOf(m: Message): String = rawTextOf(m).trim()

    private fun rawTextOf(m: Message): String =
        m.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

    /** Call only under [engineMutex]: resolving the model and building from its weights must not
     *  straddle a [useModel], or we'd build for a model already switched away from. */
    private fun engine(): Engine {
        val id = checkNotNull(current) { "no model selected — call useModel() first" }
        val path = checkNotNull(models.pathOf(id)) { "model $id is not available (ModelSource returned null)" }
        return engine ?: createEngine(id, path).also { engine = it }
    }

    /**
     * Walk the ladder. Only the runtime's own failures move us to the next rung: cancellation is
     * propagated immediately (a cancelled caller must not pay for five more native builds), and
     * JVM [Error]s such as [OutOfMemoryError] are not caught at all — after one of those, another
     * multi-GB allocation attempt is the wrong move.
     */
    private fun createEngine(modelId: String, modelPath: String): Engine {
        val ladder = engineLadder(modelPath, cacheDir, limits)
        val before = memoryProbe?.invoke()
        val startedAt = clock()
        var last: Exception? = null
        for ((i, attempt) in ladder.withIndex()) {
            try {
                val built = Engine(attempt.toRuntime()).also { it.initialize() }
                info = EngineInfo(
                    modelId = modelId,
                    modelPath = modelPath,
                    compute = attempt.compute,
                    maxTokens = attempt.maxTokens,
                    cached = attempt.cacheDir != null,
                    rungIndex = i,
                    attemptsFailed = i,
                    buildMillis = clock() - startedAt,
                    memoryBefore = before,
                    memoryAfter = memoryProbe?.invoke(),
                ).also(events::onEngineBuilt)
                return built
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                events.onEngineBuildAttemptFailed(attempt, i, ladder.size, e)
                events.onWarning("engine init attempt ${i + 1}/${ladder.size} failed (${attempt.compute}, maxTokens=${attempt.maxTokens}), trying next", e)
            }
        }
        throw last ?: IllegalStateException("engine init failed")
    }

    // ---- boundary: our pure types → the runtime's --------------------------------------------

    private fun Sampling.toRuntime(): SamplerConfig {
        val s = seed
        return if (s == null) SamplerConfig(topK = topK, topP = topP, temperature = temperature)
        else SamplerConfig(topK = topK, topP = topP, temperature = temperature, seed = s)
    }

    /** `CPU` deliberately omits `backend` so the runtime's default applies, exactly as before. */
    private fun EngineAttempt.toRuntime(): EngineConfig = when {
        compute == Compute.GPU && maxTokens != null ->
            EngineConfig(modelPath = modelPath, backend = Backend.GPU(), maxNumTokens = maxTokens, cacheDir = cacheDir)
        compute == Compute.GPU ->
            EngineConfig(modelPath = modelPath, backend = Backend.GPU(), cacheDir = cacheDir)
        maxTokens != null ->
            EngineConfig(modelPath = modelPath, maxNumTokens = maxTokens, cacheDir = cacheDir)
        else ->
            EngineConfig(modelPath = modelPath, cacheDir = cacheDir)
    }
}
