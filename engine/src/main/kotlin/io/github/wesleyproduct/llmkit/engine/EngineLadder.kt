package io.github.wesleyproduct.llmkit.engine

/** Where the model runs. `CPU` maps to the runtime's default backend. */
public enum class Compute { GPU, CPU }

/**
 * One rung of the fallback ladder: a concrete engine configuration to try.
 *
 * @property maxTokens Context cap to request, or `null` to let the runtime decide.
 * @property cacheDir Where the runtime may keep compiled graphs, or `null` for no cache.
 */
public data class EngineAttempt(
    val modelPath: String,
    val compute: Compute,
    val maxTokens: Int?,
    val cacheDir: String?,
)

/**
 * The order in which to try building an engine, widest and fastest first, so that nothing — a
 * missing GPU (no OpenCL) *or* a context size the device can't allocate — leaves us with no engine:
 *
 *     GPU + roomy → CPU + roomy → GPU + max → CPU + max → GPU (no cap) → CPU (no cap)
 *
 * A blank [cacheDir] is treated as absent.
 */
public fun engineLadder(
    modelPath: String,
    cacheDir: String?,
    limits: GenerationLimits = GenerationLimits(),
): List<EngineAttempt> {
    val cache = cacheDir?.takeIf { it.isNotBlank() }
    return listOf(
        EngineAttempt(modelPath, Compute.GPU, limits.roomyContextTokens, cache),
        EngineAttempt(modelPath, Compute.CPU, limits.roomyContextTokens, cache),
        EngineAttempt(modelPath, Compute.GPU, limits.maxContextTokens, cache),
        EngineAttempt(modelPath, Compute.CPU, limits.maxContextTokens, cache),
        EngineAttempt(modelPath, Compute.GPU, null, cache),
        EngineAttempt(modelPath, Compute.CPU, null, cache),
    )
}
