package io.github.wesleyproduct.llmkit.resume

import java.net.HttpURLConnection

/**
 * Decides whether a half-finished download may be appended to — from **what the server actually
 * answered**, not from what was requested.
 *
 * Requesting a range is not the same as receiving one. A CDN may ignore `Range`, a redirect may
 * drop the header, and the response is then a plain 200 starting at byte zero. Append that to the
 * fragment already on disk and you get a file of **the right size and the wrong contents** — a
 * 2 GB model that loads, then fails deep inside the runtime with nothing pointing back here.
 *
 * The cost of getting this wrong is high and completely silent, which is why it is a named,
 * tested function rather than a condition inlined in a copy loop.
 *
 * ```
 * val existing = target.length()
 * val conn = (URL(url).openConnection() as HttpURLConnection).apply {
 *     if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
 * }
 * val plan = ResumePlan.of(existing, conn.responseCode, conn.contentLengthLong)
 *
 * FileOutputStream(target, plan.append).use { out ->
 *     conn.inputStream.copyTo(out)   // continues from plan.startAt, or rewrites from the start
 * }
 * ```
 *
 * @property append Whether to **keep the fragment and append** rather than truncate it.
 * @property startAt Bytes already on disk, to be counted into progress.
 * @property total Full size, or `null` if unknown. A 206 reports **only the remaining** bytes, so
 *   the bytes already downloaded are added back — otherwise the percentage collapses toward zero.
 */
public data class ResumePlan(
    val append: Boolean,
    val startAt: Long,
    val total: Long?,
) {
    public companion object {
        /**
         * @param existingBytes Size of the fragment on disk; 0 if there is none.
         * @param responseCode The status code the server answered with.
         * @param contentLength `Content-Length`. **Remaining bytes for a 206, full size for a 200.**
         *   Pass a negative number or 0 when unknown (chunked responses).
         */
        public fun of(existingBytes: Long, responseCode: Int, contentLength: Long): ResumePlan {
            val body = contentLength.takeIf { it > 0 }
            // Only a 206 promises the body starts where we asked. Everything else starts at zero.
            val resuming = existingBytes > 0 && responseCode == HttpURLConnection.HTTP_PARTIAL
            return if (resuming) {
                ResumePlan(append = true, startAt = existingBytes, total = body?.plus(existingBytes))
            } else {
                ResumePlan(append = false, startAt = 0L, total = body)
            }
        }
    }
}
