package io.github.wesleyproduct.llmkit.engine

/**
 * How the runtime hands out streamed text.
 *
 * This cannot be told from the text itself: a model that repeats a token emits `"하"` twice, and
 * the second chunk is indistinguishable from a cumulative snapshot that happens to equal what came
 * before. Guessing drops the repetition silently, so the mode is stated rather than inferred.
 */
public enum class StreamMode {
    /** Each chunk is only the new text. This is what LiteRT-LM 0.13.x does — its callback hands
     *  every JNI message straight to the collector and keeps no buffer of its own. */
    DELTA,

    /** Each chunk is the whole text so far and replaces what came before. */
    CUMULATIVE,
}

/**
 * Turns streamed chunks into "the text so far".
 *
 * Chunks are kept untrimmed on purpose — trimming each delta would glue words together; trim the
 * final text instead.
 */
internal class StreamAccumulator(private val mode: StreamMode = StreamMode.DELTA) {
    private val acc = StringBuilder()

    /** Text accumulated so far. */
    val text: String get() = acc.toString()

    /** Merge one chunk and return the accumulated text. */
    fun push(chunk: String): String {
        when (mode) {
            StreamMode.DELTA -> acc.append(chunk)
            StreamMode.CUMULATIVE -> {
                acc.setLength(0)
                acc.append(chunk)
            }
        }
        return acc.toString()
    }
}
