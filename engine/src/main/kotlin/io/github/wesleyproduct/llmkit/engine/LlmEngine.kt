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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
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
 * **Stopping early.** When a streamed collector is cancelled or the output cap trips, the runtime is
 * told to stop (`cancelProcess`) before the engine is released — the runtime's own `Flow` does
 * nothing on cancellation, so without this the previous generation would still be running when the
 * next request acquired the engine.
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
    @Volatile private var closed = false
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
        /** Set once a reply was stopped part-way — by the output cap or by a cancelled collector. */
        @Volatile internal var interrupted: Boolean = false

        /** How much of the context window this chat has consumed, as far as is known. Safe to call
         *  from any thread while a generation is running. */
        public fun usage(): ContextUsage = tracker.snapshot()

        /**
         * Whether this conversation can still be used. Becomes `false` after a reply was stopped
         * part-way: the runtime does not roll its history back, so the model's context now holds a
         * half-finished assistant turn and every later turn would be conditioned on it.
         */
        public fun isUsable(): Boolean = !interrupted
    }

    /** A [Chat] outlived the model it was opened on: its engine — and with it the conversation's
     *  native state — is gone. Recoverable: open a new conversation. */
    public class StaleModelException : IllegalStateException("the model changed; this conversation is gone")

    /** The engine was [close]d. It cannot be reused; build a new [LlmEngine]. */
    public class EngineClosedException : IllegalStateException("this LlmEngine has been closed")

    /**
     * A reply on this [Chat] was stopped part-way, so the runtime's history holds a half-finished
     * assistant turn. Continuing would condition every later turn on it. Open a new conversation.
     */
    public class ChatInterruptedException : IllegalStateException(
        "this conversation was interrupted part-way; its context is incomplete — open a new one",
    )

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
     *
     * @throws EngineClosedException if the engine was closed.
     */
    public suspend fun useModel(modelId: String): Unit = withContext(ioDispatcher) {
        engineMutex.withLock {
            ensureOpen()
            if (current == modelId) return@withLock
            release(ReleaseReason.MODEL_SWITCHED)
            current = modelId
        }
    }

    /** Whether a model is selected and its weights are on disk. `false` once closed. */
    public fun isReady(): Boolean = !closed && current?.let { models.pathOf(it) } != null

    /** The model currently selected (loaded or not). Read without the lock — it is only a hint. */
    public fun currentModel(): String? = current

    /** Whether [close] has been called. A closed engine refuses every further call. */
    public fun isClosed(): Boolean = closed

    /**
     * A model's weights were deleted. If it is the one loaded, tear the engine down so the memory is
     * actually reclaimed — otherwise the engine would sit open on unlinked weights until the process
     * dies. No-op if some other model (or none) is loaded.
     *
     * @throws EngineClosedException if the engine was closed.
     */
    public suspend fun forget(modelId: String): Unit = withContext(ioDispatcher) {
        engineMutex.withLock {
            ensureOpen()
            if (current != modelId) return@withLock
            release(ReleaseReason.MODEL_FORGOTTEN)
            current = null
        }
    }

    /**
     * Tear everything down: the engine, and the scope that closes chats. **The instance is dead
     * afterwards.** Every call that would generate or change the model — [generate], [generateStream],
     * [startConversation], [send], [sendStream], [useModel], [forget] — throws
     * [EngineClosedException]. The two teardown paths stay quiet on purpose, because they get called
     * from code that is already tearing a screen down: [close] of a chat does nothing and reports it
     * through [EngineEvents.onWarning], and [warmUp] returns without doing anything. Build a new
     * [LlmEngine] to use a model again. Calling this twice is harmless.
     */
    public suspend fun close() {
        withContext(ioDispatcher) {
            engineMutex.withLock {
                if (closed) return@withLock
                release(ReleaseReason.CLOSED)
                current = null
                closed = true
            }
        }
        closeScope.cancel()
    }

    /** Under [engineMutex]. */
    private fun ensureOpen() {
        if (closed) throw EngineClosedException()
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
     * Holds the engine for the whole run. Cold: collect once per call.
     *
     * Stops as soon as the text reaches [maxChars]; the chunk that crossed the cap is not emitted
     * and not counted. On cancellation or cap the runtime is asked to stop before the engine is
     * released. Stats are published on normal completion and on cancellation (what was delivered is
     * real either way); a failure publishes nothing.
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
        var outcome: StreamOutcome? = null
        return channelFlow {
            engineMutex.withLock {
                ensureOpen()
                // Resolve the engine first: "no model selected" and "model not on disk" are state
                // errors, and they should surface before we touch any runtime type.
                val live = engine()
                val sampler = samplerFor(attempt, sampling, limits.seedStride).toRuntime()
                live.createConversation(ConversationConfig(samplerConfig = sampler)).use { conversation ->
                    outcome = drain(
                        source = conversation.sendMessageAsync(prompt).map { rawTextOf(it) },
                        maxChars = maxChars,
                        stop = { stopGenerating(conversation) },
                    ) { send(it) }
                }
            }
            outcome?.downstream?.let { throw it }
        }
            .onEach { stats.emission(it) }
            .onCompletion { cause ->
                if (cause == null || cause is CancellationException) {
                    publish(stats.finish(truncatedByCap = outcome?.capped == true))
                }
            }
            .flowOn(ioDispatcher)   // everything above — including the stats callback — runs on the engine's dispatcher
    }

    /**
     * Open a persistent multi-turn conversation. The runtime keeps KV cache and history inside it,
     * so context carries across turns without re-sending a transcript. Caller must [close] it.
     *
     * @param sampling Decoding for the whole conversation. The runtime fixes it when the
     *   conversation is created, so it cannot change per [send]. Prefer `limits.sampling.copy(...)`.
     * @throws EngineClosedException if the engine was closed.
     */
    public suspend fun startConversation(
        systemInstruction: String,
        sampling: Sampling = limits.sampling,
    ): Chat = withContext(ioDispatcher) {
        engineMutex.withLock {
            ensureOpen()
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
     *
     * Runs through the streamed path, so the output cap applies here too: without it a model that
     * never emits EOS would hold the engine — and every other caller, model switch and teardown
     * waiting on it — for as long as it kept talking.
     *
     * A reply stopped by the cap leaves the conversation unusable ([Chat.isUsable]); the truncated
     * text is still returned, but the next call throws [ChatInterruptedException].
     *
     * @throws StaleModelException if the model was switched since [chat] was opened.
     * @throws ChatInterruptedException if an earlier reply on this chat was stopped part-way.
     * @throws EngineClosedException if the engine was closed.
     */
    public suspend fun send(chat: Chat, message: String, maxChars: Int = limits.maxGenChars): String =
        withContext(ioDispatcher) { sendStream(chat, message, maxChars).lastOrNull().orEmpty().trim() }

    /**
     * Streamed reply: emits the text so far as the model generates it. Holds the engine for the
     * whole generation. Emissions are untrimmed; trim the final text. Cold: collect once.
     *
     * Stops at [maxChars] the same way [generateStream] does, and asks the runtime to stop before
     * releasing the engine. Bookkeeping follows what actually reached the conversation: a stale
     * chat counts nothing; a runtime failure after the message was sent counts the input but no
     * output and publishes no stats; normal completion and cancellation count both.
     *
     * Stopping a reply part-way — cap or cancellation — marks the chat unusable: the runtime keeps
     * the partial assistant turn in its history and will not roll it back, so every later turn
     * would be conditioned on half a sentence. Check [Chat.isUsable] and open a new conversation.
     *
     * @throws StaleModelException if the model was switched since [chat] was opened.
     * @throws ChatInterruptedException if an earlier reply on this chat was stopped part-way.
     * @throws EngineClosedException if the engine was closed.
     */
    public fun sendStream(chat: Chat, message: String, maxChars: Int = limits.maxGenChars): Flow<String> {
        val stats = StatsRecorder(attempt = 0, now = clock)
        var outcome: StreamOutcome? = null
        var finalText = ""
        var inputCounted = false
        return channelFlow {
            engineMutex.withLock {
                ensureOpen()
                if (chat.epoch != epoch) throw StaleModelException()
                if (chat.interrupted) throw ChatInterruptedException()
                chat.tracker.addInput(message)
                inputCounted = true
                try {
                    outcome = drain(
                        source = chat.conversation.sendMessageAsync(message).map { rawTextOf(it) },
                        maxChars = maxChars,
                        stop = { stopGenerating(chat.conversation) },
                    ) {
                        finalText = it
                        send(it)
                    }
                } catch (t: Throwable) {
                    // The message already reached the conversation. Whatever the runtime committed
                    // before it failed stays in its history, so this chat cannot be continued
                    // honestly either — mark it before the failure leaves.
                    chat.interrupted = true
                    throw t
                }
                // The runtime keeps whatever it committed before we told it to stop, so this
                // conversation can no longer be continued honestly.
                if (outcome?.stoppedEarly == true) chat.interrupted = true
            }
            outcome?.downstream?.let { throw it }
        }
            .onEach { stats.emission(it) }
            .onCompletion { cause ->
                if (inputCounted && (cause == null || cause is CancellationException)) {
                    chat.tracker.addOutput(finalText)
                    publish(stats.finish(truncatedByCap = outcome?.capped == true))
                }
            }
            .flowOn(ioDispatcher)
    }

    /**
     * Release a chat's native resources. `Engine` is final in the runtime AAR, so we can't see what
     * its own `close()` does to conversations it created — we assume it tears them down too, and
     * skip closing a stale chat rather than risk a double free. Worst case if that assumption is
     * wrong is a leak, which is the side to be wrong on. Failures are reported via
     * [EngineEvents.onWarning], never thrown. After the engine itself is [close]d this is a no-op
     * and is reported as such.
     */
    public fun close(chat: Chat) {
        if (closed || !closeScope.isActive) {
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
     * doesn't pay the multi-second cold load. No-op when nothing is downloaded or the engine is
     * closed; cheap once built. Same exception discipline as the ladder: cancellation and JVM
     * [Error]s propagate; only the runtime's own failures are reported as a warning.
     */
    public suspend fun warmUp() {
        if (!isReady()) return
        withContext(ioDispatcher) {
            try {
                engineMutex.withLock {
                    ensureOpen()
                    engine()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                events.onWarning("warmUp failed", e)
            }
        }
    }

    // ---- internals ---------------------------------------------------------------------------

    /** What a streamed generation ended up doing. */
    internal class StreamOutcome(val capped: Boolean, val downstream: Throwable?) {
        /** The runtime was told to stop rather than being allowed to finish. */
        val stoppedEarly: Boolean get() = capped || downstream != null
    }

    /**
     * Run one generation to the end of the runtime's stream, delivering text through [emit] until
     * the cap trips or the collector goes away.
     *
     * **It always waits for the runtime's stream to finish**, even when we stopped wanting the
     * text. `cancelProcess()` returns `Unit` and promises nothing about when generation actually
     * ends, so returning early would let the conversation — and the engine behind it — be closed
     * while a native callback is still running. Draining under [NonCancellable] is what makes
     * closing safe, and holding [engineMutex] throughout is what keeps the next request out.
     *
     * The cost is honest: after the cap or a cancellation this can still take as long as the
     * runtime needs to wind down.
     *
     * Takes raw chunks and a [stop] callback rather than the runtime's own types, so the one path
     * that cannot be exercised on a device — a stream that dies the instant it is told to stop —
     * can be reproduced with a plain [Flow] in a unit test.
     */
    internal suspend fun drain(
        source: Flow<String>,
        maxChars: Int,
        stop: () -> Unit,
        emit: suspend (String) -> Unit,
    ): StreamOutcome {
        val acc = StreamAccumulator(limits.streamMode)
        var capped = false
        var downstream: Throwable? = null
        var stopRequested = false
        withContext(NonCancellable) {
            try {
                source.collect { chunk ->
                    val text = acc.push(chunk)
                    if (stopRequested) return@collect                      // draining: read, deliver nothing
                    if (text.length >= maxChars) {
                        capped = true
                        stopRequested = true
                        stop()
                        return@collect
                    }
                    try {
                        emit(text)
                    } catch (t: Throwable) {                               // collector gone, or cancelled
                        downstream = t
                        stopRequested = true
                        stop()
                    }
                }
            } catch (t: Throwable) {
                // We asked the runtime to stop, so the stream ending abruptly IS the answer to that
                // request — LiteRT-LM's callbackFlow most often ends it as a CancellationException.
                // Letting it out here would throw away the outcome: the caller would never learn the
                // reply was cut, and a Chat would go on looking usable with half an assistant turn
                // already in its history. Only a stream we did NOT stop is a real failure.
                if (t is Error || !stopRequested) throw t
                events.onWarning("the runtime stream ended abruptly after cancelProcess", t)
            }
        }
        return StreamOutcome(capped, downstream)
    }

    /**
     * Ask the runtime to stop generating. Its own `Flow` is a `callbackFlow` whose `awaitClose`
     * body is empty, so cancelling the collector detaches from the stream without stopping the work
     * behind it. This only *asks*; [drain] is what waits. Never throws — we are already unwinding.
     */
    private fun stopGenerating(conversation: Conversation) {
        runCatching { conversation.cancelProcess() }
            .onFailure { events.onWarning("cancelProcess failed", it) }
    }

    private fun publish(stats: GenerationStats) {
        lastStats = stats
        events.onGeneration(stats)
    }

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
