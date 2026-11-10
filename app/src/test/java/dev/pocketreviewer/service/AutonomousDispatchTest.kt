package dev.pocketreviewer.service

import dev.pocketreviewer.data.ReviewEntry
import dev.pocketreviewer.util.PullRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutonomousDispatchTest {

    // ---- newestMessageText ----
    @Test fun `newest message wins over older grouped messages`() {
        assertEquals(
            "PR2 https://github.com/acme/x/pull/2",
            AutonomousDispatch.newestMessageText(
                messageTexts = listOf(
                    "PR1 https://github.com/acme/x/pull/1",
                    "PR2 https://github.com/acme/x/pull/2"
                ),
                text = "ignored", bigText = "ignored"
            )
        )
    }

    @Test fun `blank trailing messages are skipped`() {
        assertEquals(
            "real https://github.com/acme/x/pull/9",
            AutonomousDispatch.newestMessageText(
                messageTexts = listOf("real https://github.com/acme/x/pull/9", "  "),
                text = "t", bigText = "b"
            )
        )
    }

    @Test fun `falls back to text then bigText when no messages`() {
        assertEquals("t", AutonomousDispatch.newestMessageText(emptyList(), "t", "b"))
        assertEquals("b", AutonomousDispatch.newestMessageText(emptyList(), "  ", "b"))
    }

    // ---- shouldSkipAutonomousReview ----
    private fun entry(key: String, merged: Boolean) =
        ReviewEntry(prKey = key, prUrl = "u", verdict = "APPROVE", reviewText = "",
            timestampMs = 0L, wasMerged = merged)

    @Test fun `fresh PR is not skipped`() {
        assertFalse(AutonomousDispatch.shouldSkipAutonomousReview("o/r#1", emptyList(), emptySet()))
    }

    @Test fun `in-flight PR is skipped`() {
        assertTrue(AutonomousDispatch.shouldSkipAutonomousReview("o/r#1", emptyList(), setOf("o/r#1")))
    }

    @Test fun `already-merged PR is skipped`() {
        assertTrue(AutonomousDispatch.shouldSkipAutonomousReview(
            "o/r#1", listOf(entry("o/r#1", merged = true)), emptySet()))
    }

    @Test fun `reviewed-but-not-merged PR is NOT skipped - re-review allowed`() {
        assertFalse(AutonomousDispatch.shouldSkipAutonomousReview(
            "o/r#1", listOf(entry("o/r#1", merged = false)), emptySet()))
    }

    @Test fun `another PR merged does not skip this one`() {
        assertFalse(AutonomousDispatch.shouldSkipAutonomousReview(
            "o/r#1", listOf(entry("o/r#2", merged = true)), emptySet()))
    }

    // ---- isOwnThreadReply ----
    @Test fun `own reply base-only is recognized`() {
        assertTrue(AutonomousDispatch.isOwnThreadReply("left comments", "left comments"))
    }

    @Test fun `own reply with appended PR url is recognized`() {
        assertTrue(AutonomousDispatch.isOwnThreadReply(
            "left comments — https://github.com/acme/r/pull/1", "left comments"))
    }

    @Test fun `own reply with leading whitespace is recognized`() {
        assertTrue(AutonomousDispatch.isOwnThreadReply("  left comments — x", "left comments"))
    }

    @Test fun `custom base is recognized`() {
        assertTrue(AutonomousDispatch.isOwnThreadReply("reviewed 👀 — u", "reviewed 👀"))
    }

    @Test fun `a human message is not our reply`() {
        assertFalse(AutonomousDispatch.isOwnThreadReply(
            "look at this PR https://github.com/acme/r/pull/1", "left comments"))
    }

    @Test fun `blank base never matches`() {
        assertFalse(AutonomousDispatch.isOwnThreadReply("left comments", ""))
        assertFalse(AutonomousDispatch.isOwnThreadReply("left comments", "   "))
    }

    // ---- isOwnThreadReply (multi-base) ----
    @Test fun `any base in the list matches`() {
        val bases = listOf("left comments", "⚠️ Has merge conflicts — it can't be merged. Needs manual resolution.")
        assertTrue(AutonomousDispatch.isOwnThreadReply("left comments — u", bases))
        assertTrue(AutonomousDispatch.isOwnThreadReply(
            "⚠️ Has merge conflicts — it can't be merged. Needs manual resolution.", bases))
    }

    @Test fun `unrelated text matches no base`() {
        assertFalse(AutonomousDispatch.isOwnThreadReply(
            "look at this PR https://github.com/acme/r/pull/1",
            listOf("left comments", "⚠️ I couldn't approve the PR:")))
    }

    @Test fun `empty and blank base lists never match`() {
        assertFalse(AutonomousDispatch.isOwnThreadReply("left comments", emptyList()))
        assertFalse(AutonomousDispatch.isOwnThreadReply("left comments", listOf("", "   ")))
    }

    // ---- resolveWatchedPR ----
    private fun pr(key: String) = PullRequest.fromKey(key, "https://github.com/$key")

    @Test fun `watched thread resolves by thread id`() {
        val pr = pr("o/r#1")
        assertEquals(pr, AutonomousDispatch.resolveWatchedPR(
            threadId = "spaces/S/threads/T", spaceName = "spaces/S",
            watchedThreads = mapOf("spaces/S/threads/T" to pr)))
    }

    @Test fun `unwatched thread id falls back to watched space`() {
        val pr = pr("o/r#2")
        assertEquals(pr, AutonomousDispatch.resolveWatchedPR(
            threadId = "spaces/S/threads/OTHER", spaceName = "spaces/S",
            watchedThreads = mapOf("spaces/S" to pr)))
    }

    @Test fun `null thread id falls back to watched space`() {
        val pr = pr("o/r#3")
        assertEquals(pr, AutonomousDispatch.resolveWatchedPR(
            threadId = null, spaceName = "spaces/S",
            watchedThreads = mapOf("spaces/S" to pr)))
    }

    @Test fun `watched thread wins over watched space`() {
        val threadPr = pr("o/r#4")
        assertEquals(threadPr, AutonomousDispatch.resolveWatchedPR(
            threadId = "spaces/S/threads/T", spaceName = "spaces/S",
            watchedThreads = mapOf("spaces/S/threads/T" to threadPr, "spaces/S" to pr("o/r#5"))))
    }

    @Test fun `nothing watched resolves to null`() {
        assertEquals(null, AutonomousDispatch.resolveWatchedPR(
            threadId = "spaces/S/threads/T", spaceName = "spaces/S",
            watchedThreads = emptyMap()))
    }

    @Test fun `null thread id and null space resolve to null`() {
        assertEquals(null, AutonomousDispatch.resolveWatchedPR(
            threadId = null, spaceName = null,
            watchedThreads = mapOf("spaces/S" to pr("o/r#6"))))
    }
}
