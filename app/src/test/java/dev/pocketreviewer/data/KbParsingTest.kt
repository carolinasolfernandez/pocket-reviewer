package dev.pocketreviewer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KbParsingTest {

    @Test fun `parses owner slash repo and github urls`() {
        assertEquals("acme" to "handbook", KbParsing.parseRepo("acme/handbook"))
        assertEquals("acme" to "handbook", KbParsing.parseRepo(" https://github.com/acme/handbook/ "))
        assertEquals("acme" to "handbook", KbParsing.parseRepo("https://github.com/acme/handbook.git"))
        assertNull(KbParsing.parseRepo("handbook"))
        assertNull(KbParsing.parseRepo("https://gitlab.com/acme/handbook"))
        assertNull(KbParsing.parseRepo(""))
    }

    @Test fun `blank path takes every markdown file`() {
        assertTrue(KbParsing.matchesPath("README.md", ""))
        assertTrue(KbParsing.matchesPath("docs/rules/a.MD", ""))
        assertFalse(KbParsing.matchesPath("src/main.kt", ""))
    }

    @Test fun `folder path takes markdown under it only`() {
        assertTrue(KbParsing.matchesPath("docs/rules/a.md", "/docs/rules/"))
        assertTrue(KbParsing.matchesPath("docs/rules/deep/b.mdx", "docs/rules"))
        assertFalse(KbParsing.matchesPath("docs/rules-old/a.md", "docs/rules"))
        assertFalse(KbParsing.matchesPath("docs/rules/a.png", "docs/rules"))
    }

    @Test fun `a single file path matches exactly, whatever its extension`() {
        assertTrue(KbParsing.matchesPath("REVIEW_RULES.txt", "REVIEW_RULES.txt"))
        assertFalse(KbParsing.matchesPath("REVIEW_RULES.txt.bak", "REVIEW_RULES.txt"))
    }

    @Test fun `display name is relative to the folder`() {
        assertEquals("deep/b.md", KbParsing.displayName("docs/rules/deep/b.md", "docs/rules/"))
        assertEquals("README.md", KbParsing.displayName("README.md", ""))
    }

    @Test fun `join and split round trip`() {
        val files = listOf("a.md" to "# A\nalpha", "dir/b.md" to "beta")
        assertEquals(files, KbParsing.split(KbParsing.join(files), "fallback"))
    }

    @Test fun `text without headers is one file`() {
        assertEquals(listOf("custom text" to "just rules"), KbParsing.split("  just rules \n", "custom text"))
    }

    @Test fun `compose appends extra instructions only when present`() {
        assertEquals("base", KbParsing.compose(" base ", "  "))
        assertEquals("base", KbParsing.compose("base", null))
        val composed = KbParsing.compose("base", "be strict")
        assertTrue(composed.startsWith("base\n"))
        assertTrue(composed.contains("ADDITIONAL INSTRUCTIONS"))
        assertTrue(composed.endsWith("be strict"))
    }
}
