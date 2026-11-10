package dev.pocketreviewer.service

import dev.pocketreviewer.service.MergeAggregator.Completion
import org.junit.Assert.assertEquals
import org.junit.Test

class MergeAggregatorTest {

    @Test fun `single PR merged completes as ALL_MERGED`() {
        val agg = MergeAggregator(listOf("o/r#1"))
        assertEquals(Completion.ALL_MERGED, agg.record("o/r#1", merged = true))
    }

    @Test fun `single PR not merged completes as DONE_NOT_ALL_MERGED`() {
        val agg = MergeAggregator(listOf("o/r#1"))
        assertEquals(Completion.DONE_NOT_ALL_MERGED, agg.record("o/r#1", merged = false))
    }

    @Test fun `two PRs both merged - first PENDING then ALL_MERGED`() {
        val agg = MergeAggregator(listOf("o/r#1", "o/r#2"))
        assertEquals(Completion.PENDING, agg.record("o/r#1", merged = true))
        assertEquals(Completion.ALL_MERGED, agg.record("o/r#2", merged = true))
    }

    @Test fun `two PRs one not merged - never ALL_MERGED`() {
        val agg = MergeAggregator(listOf("o/r#1", "o/r#2"))
        assertEquals(Completion.PENDING, agg.record("o/r#1", merged = true))
        assertEquals(Completion.DONE_NOT_ALL_MERGED, agg.record("o/r#2", merged = false))
    }

    @Test fun `order independent - not-merged first still never ALL_MERGED`() {
        val agg = MergeAggregator(listOf("o/r#1", "o/r#2"))
        assertEquals(Completion.PENDING, agg.record("o/r#1", merged = false))
        assertEquals(Completion.DONE_NOT_ALL_MERGED, agg.record("o/r#2", merged = true))
    }

    @Test fun `idempotent - first outcome wins and completes only once`() {
        val agg = MergeAggregator(listOf("o/r#1"))
        assertEquals(Completion.DONE_NOT_ALL_MERGED, agg.record("o/r#1", merged = false))
        // a later (fallback) report must not flip the result or re-fire
        assertEquals(Completion.DONE_NOT_ALL_MERGED, agg.record("o/r#1", merged = true))
    }

    @Test fun `ALL_MERGED fires at most once`() {
        val agg = MergeAggregator(listOf("o/r#1"))
        assertEquals(Completion.ALL_MERGED, agg.record("o/r#1", merged = true))
        assertEquals(Completion.DONE_NOT_ALL_MERGED, agg.record("o/r#1", merged = true))
    }

    @Test fun `unexpected keys do not complete the set`() {
        val agg = MergeAggregator(listOf("o/r#1"))
        assertEquals(Completion.PENDING, agg.record("o/r#99", merged = true))
        assertEquals(Completion.ALL_MERGED, agg.record("o/r#1", merged = true))
    }
}
