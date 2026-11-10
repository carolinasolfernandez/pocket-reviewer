package dev.pocketreviewer.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewPromptTest {

    @Test fun `system prompt embeds the knowledge base and the language`() {
        val s = ReviewPrompt.system("RULE-1 do the thing", "Español")
        assertTrue(s.contains("RULE-1 do the thing"))
        assertTrue(s.contains("Write the review in Español"))
        assertTrue(s.contains("`APPROVE`, `REQUEST_CHANGES`, or `COMMENT`"))
    }

    @Test fun `blank language defaults to English`() {
        assertTrue(ReviewPrompt.system("kb", "  ").contains("Write the review in English"))
    }

    @Test fun `system prompt is stable for the same configuration`() {
        // The system half must not depend on the PR, or prompt caching never hits.
        assertEquals(ReviewPrompt.system("kb", "English"), ReviewPrompt.system("kb", "English"))
    }

    @Test fun `user prompt carries the PR data`() {
        val u = ReviewPrompt.user(
            title = "Add login", author = "ana", headRef = "feat/login", baseRef = "main",
            body = "desc", fileList = "- a.kt (+1/-0)", diffText = "+val x = 1", stack = listOf("Kotlin")
        )
        assertTrue(u.contains("**Title:** Add login"))
        assertTrue(u.contains("feat/login → main"))
        assertTrue(u.contains("Kotlin"))
        assertTrue(u.contains("+val x = 1"))
        assertFalse(u.contains("truncated"))
    }

    @Test fun `long diffs are truncated and flagged`() {
        val u = ReviewPrompt.user("t", "a", "h", "b", "d", "f", "x".repeat(ReviewPrompt.MAX_DIFF_CHARS + 10), emptyList())
        assertTrue(u.contains("truncated"))
        assertFalse(u.contains("x".repeat(ReviewPrompt.MAX_DIFF_CHARS + 1)))
        assertTrue(u.contains("not detected"))
    }

    @Test fun `detects languages by extension, most frequent first`() {
        val stack = ReviewPrompt.detectStack(listOf("a.py", "b.py", "c.tsx", "Dockerfile", "notes.unknown"))
        assertEquals(listOf("Python", "TypeScript", "Docker"), stack)
    }

    @Test fun `ignored checks match by substring, case insensitive`() {
        assertTrue(ReviewManager.isIgnoredCheck("security/Snyk (acme)", listOf("snyk")))
        assertFalse(ReviewManager.isIgnoredCheck("build", listOf("snyk")))
        assertFalse(ReviewManager.isIgnoredCheck("build", emptyList()))
        assertFalse(ReviewManager.isIgnoredCheck("build", listOf(" ")))
    }
}
