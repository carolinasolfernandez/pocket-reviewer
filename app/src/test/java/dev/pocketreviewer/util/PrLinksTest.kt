package dev.pocketreviewer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrLinksTest {

    @Test fun `finds PRs from any owner`() {
        val prs = PrLinks.findAll("see https://github.com/acme/api/pull/12 and https://github.com/other-org/web.app/pull/3")
        assertEquals(listOf("acme/api#12", "other-org/web.app#3"), prs.map { it.key })
        assertEquals("https://github.com/acme/api/pull/12", prs[0].url)
        assertEquals(12, prs[0].number)
    }

    @Test fun `deduplicates the same PR and ignores url suffixes`() {
        val prs = PrLinks.findAll(
            "https://github.com/acme/api/pull/12/files https://github.com/acme/api/pull/12#discussion_r1"
        )
        assertEquals(1, prs.size)
        assertEquals("https://github.com/acme/api/pull/12", prs[0].url)
    }

    @Test fun `ignores non PR github links`() {
        assertTrue(PrLinks.findAll("https://github.com/acme/api/issues/12 https://github.com/acme/api").isEmpty())
    }

    @Test fun `parse accepts a url with surrounding whitespace`() {
        assertEquals("acme/api#7", PrLinks.parse("  https://github.com/acme/api/pull/7 \n")?.key)
        assertNull(PrLinks.parse("not a url"))
    }

    @Test fun `empty owner list allows every owner`() {
        val pr = PrLinks.parse("https://github.com/anyone/repo/pull/1")!!
        assertTrue(PrLinks.isAllowed(pr, emptyList(), emptyList()))
    }

    @Test fun `owner allowlist is case insensitive`() {
        val pr = PrLinks.parse("https://github.com/Acme/repo/pull/1")!!
        assertTrue(PrLinks.isAllowed(pr, listOf("acme"), emptyList()))
        assertFalse(PrLinks.isAllowed(pr, listOf("other"), emptyList()))
    }

    @Test fun `ignored repos match by name or by owner slash name`() {
        val pr = PrLinks.parse("https://github.com/acme/docs/pull/1")!!
        assertFalse(PrLinks.isAllowed(pr, emptyList(), listOf("docs")))
        assertFalse(PrLinks.isAllowed(pr, emptyList(), listOf("ACME/Docs")))
        assertTrue(PrLinks.isAllowed(pr, emptyList(), listOf("other/docs")))
        assertTrue(PrLinks.isAllowed(pr, emptyList(), listOf("doc")))
    }

    @Test fun `find applies both filters`() {
        val text = "https://github.com/acme/api/pull/1 https://github.com/acme/docs/pull/2 https://github.com/evil/api/pull/3"
        assertEquals(listOf("acme/api#1"), PrLinks.find(text, listOf("acme"), listOf("docs")).map { it.key })
    }
}
