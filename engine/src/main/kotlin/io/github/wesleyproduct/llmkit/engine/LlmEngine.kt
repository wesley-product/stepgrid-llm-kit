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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.takeWhile
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
 * What it *does* know, it reports: [currentEngine] says which backend and context size actually
 * came up and what that cost; every generation produces a [GenerationStats]; every [Chat] tracks its
 * [ContextUsage]. See [EngineEvents] to receive these as they happen.
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
    private val closeScope = CoroutineScope(ioDispatcher + SupervisorJob())

    /** A live conversation, tagged with the engine generation that created it. */
    public class Chat internal constructor(
        internal val conversation: Conversation,
        internal val epoch: Int,
        internal val tracker: ContextUsageTracker,
    ) {
        /** How much of the context window this chat has consumed, as far as is known. */
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
    public suspend fun useModel(modelId: String): Unit = engineMutex.withLock {
        if (current == modelId) return@withLock
        release(ReleaseReason.MODEL_SWITCHED)
        current = modelId
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
    public suspend fun forget(modelId: String): Unit = engineMutex.withLock {
        if (current != modelId) return@withLock
        release(ReleaseReason.MODEL_FORGOTTEN)
        current = null
    }

    /** Under [engineMutex]. */
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
     */
    public suspend fun generate(prompt: String, attempt: Int = 0): String =
        withContext(ioDispatcher) { generateStream(prompt, attempt).lastOrNull().orEmpty() }

    /**
     * Streamed one-shot generation. Each emission is the raw text SO FAR (see [StreamAccumulator]).
     * Holds the engine for the whole run. Cold: collect once per call.
     */
    public fun generateStream(prompt: String, attempt: Int = 0): Flow<String> {
        val stats = StatsRecorder(attempt, clock)
        return flow {
            engineMutex.withLock {
                val sampler = samplerFor(attempt, limits.sampling, limits.seedStride).toRuntime()
                engine().createConversation(ConversationConfig(samplerConfig = sampler)).use { conversation ->
                    val acc = StreamAccumulator()
                    conversation.sendMessageAsync(prompt).collect {
                        val text = acc.push(rawTextOf(it))
                        stats.emission(text)
                        emit(text)
                    }
                }
            }
        }.flowOn(ioDispatcher)
            .takeWhile { it.length < limits.maxGenChars }
            .onCompletion { publish(stats.finish(limits.maxGenChars)) }
    }

    /**
     * Open a persistent multi-turn conversation. The runtime keeps KV cache and history inside it,
     * so context carries across turns without re-sending a transcript. Caller must [close] it.
     */
    public suspend fun startConversation(systemInstruction: String): Chat = withContext(ioDispatcher) {
        engineMutex.withLock {
            val conversation = engine().createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(systemInstruction),
                    samplerConfig = limits.sampling.toRuntime(),
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
            publish(stats.finish(Int.MAX_VALUE))
            reply
        }
    }

    /**
     * Streamed reply: emits the text so far as the model generates it. Holds the engine for the
     * whole generation, like [send]. Emissions are untrimmed; trim the final text. Cold: collect once.
     * @throws StaleModelException if the model was switched since [chat] was opened.
     */
    public fun sendStream(chat: Chat, message: String, maxChars: Int = limits.maxGenChars): Flow<String> {
        val stats = StatsRecorder(attempt = 0, now = clock)
        var finalText = ""
        return flow {
            engineMutex.withLock {
                if (chat.epoch != epoch) throw StaleModelException()
                chat.tracker.addInput(message)
                val acc = StreamAccumulator()
                chat.conversation.sendMessageAsync(message).collect {
                    val text = acc.push(rawTextOf(it))
                    finalText = text
                    stats.emission(text)
                    emit(text)
                }
            }
        }.flowOn(ioDispatcher)
            .takeWhile { it.length < maxChars }
            .onCompletion {
                chat.tracker.addOutput(finalText)
                publish(stats.finish(maxChars))
            }
    }

    /**
     * Release a chat's native resources. `Engine` is final in the runtime AAR, so we can't see what
     * its own `close()` does to conversations it created — we assume it tears them down too, and
     * skip closing a stale chat rather than risk a double free. Worst case if that assumption is
     * wrong is a leak, which is the side to be wrong on.
     */
    public fun close(chat: Chat) {
        closeScope.launch {
            engineMutex.withLock {
                if (chat.epoch == epoch) chat.conversation.close()
            }
        }
    }

    /**
     * Build the engine for the selected model ahead of time (no generation) so the first real call
     * doesn't pay the multi-second cold load. No-op when nothing is downloaded; cheap once built.
     */
    public suspend fun warmUp() {
        if (!isReady()) return
        withContext(ioDispatcher) {
            runCatching { engineMutex.withLock { engine() } }
                .onFailure {
                    // A cancelled caller must see its cancellation, not a logged warm-up failure.
                    if (it is CancellationException) throw it
                    events.onWarning("warmUp failed", it)
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
        val id = requireNotNull(current) { "no model selected" }
        val path = requireNotNull(models.pathOf(id)) { "model $id is not available" }
        return engine ?: createEngine(id, path).also { engine = it }
    }

    private fun createEngine(modelId: String, modelPath: String): Engine {
        val ladder = engineLadder(modelPath, cacheDir, limits)
        val before = memoryProbe?.invoke()
        val startedAt = clock()
        var last: Throwable? = null
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
            } catch (t: Throwable) {
                last = t
                events.onEngineBuildAttemptFailed(attempt, i, ladder.size, t)
                events.onWarning("engine init attempt ${i + 1}/${ladder.size} failed (${attempt.compute}, maxTokens=${attempt.maxTokens}), trying next", t)
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
