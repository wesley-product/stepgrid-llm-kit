package io.github.wesleyproduct.llmkit.engine

/**
 * Decoding parameters. Deliberately our own type rather than the runtime's `SamplerConfig`, so the
 * pure layer (ladders, tests) never touches the native SDK; [LlmEngine] maps it at the boundary.
 *
 * Defaults are what StepGrid ships for Gemma E2B/E4B: temperature 0.7 (lower amplifies small-model
 * repetition rather than curbing it, and this runtime has no repetition penalty), and a tightened
 * nucleus (16 / 0.7) because the wider 20 / 0.8 let rare long-tail tokens from other scripts leak
 * into Korean sentences. [seed] `null` means the runtime's fixed default seed.
 */
public data class Sampling(
    val topK: Int = 16,
    val topP: Double = 0.7,
    val temperature: Double = 0.7,
    val seed: Int? = null,
)

/**
 * Everything that bounds a generation. These are inputs, not constants: the right values depend on
 * the model and on how much RAM the device can give the KV cache.
 *
 * @property maxContextTokens Hard input+output ceiling that every bundled model accepts. The
 *   fallback ladder ends here before dropping the cap entirely.
 * @property roomyContextTokens Tried first. A wider window costs KV-cache memory (4096 is twice
 *   2048), so it is a rung on the ladder, not a replacement — devices that can't allocate it
 *   land on [maxContextTokens].
 * @property maxGenChars Client-side output cap. The runtime exposes no max-output-tokens knob, so
 *   this is what stops a repetition spiral from holding the engine for 30s+.
 * @property seedStride Distance between seeds of successive retries. Adjacent seeds (0, 1) were
 *   observed to yield byte-identical output; far-apart primes don't.
 */
public data class GenerationLimits(
    val maxContextTokens: Int = 2048,
    val roomyContextTokens: Int = 4096,
    val maxGenChars: Int = 1000,
    val seedStride: Int = 7919,
    val sampling: Sampling = Sampling(),
)

/**
 * Sampling for the n-th attempt at the same prompt.
 *
 * The runtime's seed is fixed unless you pass one, so re-asking with the same prompt returns the
 * same text character-for-character — a retry loop that never actually retries. Attempt 0 keeps
 * [base] untouched (most turns end there and must stay reproducible); later attempts move the seed
 * by [seedStride] each.
 */
public fun samplerFor(attempt: Int, base: Sampling, seedStride: Int): Sampling =
    if (attempt <= 0) base else base.copy(seed = attempt * seedStride)
