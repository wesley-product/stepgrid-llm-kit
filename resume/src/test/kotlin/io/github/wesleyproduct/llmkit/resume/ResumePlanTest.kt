package io.github.wesleyproduct.llmkit.resume

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResumePlanTest {

    /** Why this code exists: models are 1–3.5 GB, so bytes already downloaded must survive a retry. */
    @Test
    fun `a 206 resumes from what is already on disk`() {
        val plan = ResumePlan.of(existingBytes = 1_500_000_000, responseCode = 206, contentLength = 500_000_000)

        assertTrue(plan.append)
        assertEquals(1_500_000_000L, plan.startAt)
    }

    /**
     * A 206 reports **only the remaining** bytes. Use that as the total and a nearly finished
     * download drops back to about 0% — which the user reads as "starting over", the very thing
     * resuming was meant to fix.
     */
    @Test
    fun `a 206 adds the bytes already downloaded back into the total`() {
        val plan = ResumePlan.of(existingBytes = 1_500_000_000, responseCode = 206, contentLength = 500_000_000)

        assertEquals(2_000_000_000L, plan.total)
    }

    /**
     * The expensive mistake. Requesting a range does not mean receiving one — a CDN may ignore the
     * header or a redirect may drop it, and then the body starts at byte zero. Appended to the
     * fragment, that yields a file of the right size and broken contents that loads and fails much
     * later, with nothing pointing back here.
     */
    @Test
    fun `a 200 restarts from scratch even when a fragment exists`() {
        val plan = ResumePlan.of(existingBytes = 1_500_000_000, responseCode = 200, contentLength = 2_000_000_000)

        assertFalse(plan.append, "a 200 starts at byte zero - appending would corrupt the file")
        assertEquals(0L, plan.startAt)
        assertEquals(2_000_000_000L, plan.total)
    }

    /** Nothing on disk: an ordinary first attempt. */
    @Test
    fun `no fragment means a plain download from the start`() {
        val plan = ResumePlan.of(existingBytes = 0, responseCode = 200, contentLength = 2_000_000_000)

        assertFalse(plan.append)
        assertEquals(0L, plan.startAt)
    }

    /** A 206 with nothing downloaded is contradictory. Do not trust it; download fresh. */
    @Test
    fun `a 206 with no fragment is not treated as a resume`() {
        val plan = ResumePlan.of(existingBytes = 0, responseCode = 206, contentLength = 2_000_000_000)

        assertFalse(plan.append)
        assertEquals(0L, plan.startAt)
    }

    /** Chunked responses carry no Content-Length. Unknown is not zero — progress is unavailable, the copy still runs. */
    @Test
    fun `an unknown size leaves total null`() {
        val plan = ResumePlan.of(existingBytes = 0, responseCode = 200, contentLength = -1)

        assertEquals(null, plan.total)
    }

    @Test
    fun `an unknown size still keeps the downloaded bytes when resuming`() {
        val plan = ResumePlan.of(existingBytes = 1_000, responseCode = 206, contentLength = -1)

        assertTrue(plan.append)
        assertEquals(1_000L, plan.startAt)
        assertEquals(null, plan.total)
    }
}
