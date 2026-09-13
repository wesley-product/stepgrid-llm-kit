package io.github.wesleyproduct.llmkit.engine

/**
 * Where model weights live. The engine only ever needs a path for an id; how models are named,
 * downloaded or deleted is the app's business.
 *
 * Return `null` when the model isn't available (not downloaded, deleted) — that is also how the
 * engine answers [LlmEngine.isReady].
 */
public fun interface ModelSource {
    /** Absolute path of the weights for [modelId], or `null` if they are not on disk (yet). */
    public fun pathOf(modelId: String): String?
}
