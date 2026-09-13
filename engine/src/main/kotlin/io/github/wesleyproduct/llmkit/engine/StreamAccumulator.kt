package io.github.wesleyproduct.llmkit.engine

/**
 * Assembles streamed generation into "the text so far", whether the runtime hands out deltas or
 * cumulative snapshots — it has done both across versions, and a consumer shouldn't have to know.
 *
 * Rule: a chunk that starts with everything accumulated so far is a snapshot and replaces it;
 * anything else is a delta and is appended. Chunks are kept untrimmed on purpose — trimming each
 * delta would glue words together; trim the final text instead.
 */
internal class StreamAccumulator {
    private val acc = StringBuilder()

    /** Text accumulated so far. */
    public val text: String get() = acc.toString()

    /** Merge one chunk and return the accumulated text. */
    public fun push(chunk: String): String {
        if (chunk.length >= acc.length && chunk.startsWith(acc)) {
            acc.setLength(0)
            acc.append(chunk)
        } else {
            acc.append(chunk)
        }
        return acc.toString()
    }
}
