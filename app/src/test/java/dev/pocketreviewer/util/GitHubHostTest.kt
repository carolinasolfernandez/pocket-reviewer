package dev.pocketreviewer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubHostTest {

    @Test fun `blank or garbage falls back to github dot com`() {
        assertEquals("github.com", GitHubHost.normalize(null))
        assertEquals("github.com", GitHubHost.normalize("  "))
        assertEquals("github.com", GitHubHost.normalize("not a host!"))
    }

    @Test fun `strips scheme, path, case and whitespace`() {
        assertEquals("github.acme.com", GitHubHost.normalize(" https://GitHub.Acme.com/ "))
        assertEquals("github.acme.com", GitHubHost.normalize("http://github.acme.com/api/v3"))
        assertEquals("ghe.local:8443", GitHubHost.normalize("ghe.local:8443"))
    }

    @Test fun `api base differs between github dot com and enterprise`() {
        assertEquals("https://api.github.com", GitHubHost.apiBase("github.com"))
        assertEquals("https://api.github.com", GitHubHost.apiBase(""))
        assertEquals("https://github.acme.com/api/v3", GitHubHost.apiBase("https://github.acme.com/"))
        assertFalse(GitHubHost.isEnterprise("GITHUB.COM"))
        assertTrue(GitHubHost.isEnterprise("github.acme.com"))
        assertEquals("https://github.acme.com", GitHubHost.webBase("github.acme.com"))
    }

    @Test fun `PR links are recognized on the configured host only`() {
        val text = "https://github.acme.com/team/api/pull/5 and https://github.com/team/api/pull/6"
        assertEquals(listOf("team/api#5"), PrLinks.findAll(text, "github.acme.com").map { it.key })
        assertEquals(listOf("team/api#6"), PrLinks.findAll(text).map { it.key })
        assertEquals("https://github.acme.com/team/api/pull/5", PrLinks.parse(text, "github.acme.com")?.url)
    }

    @Test fun `a lookalike host does not match`() {
        // the dot must be literal, and the host must not be a suffix of a longer one
        assertTrue(PrLinks.findAll("https://githubXacme.com/team/api/pull/5", "github.acme.com").isEmpty())
        assertNull(PrLinks.parse("https://evil-github.com.attacker.io/team/api/pull/5"))
        assertNull(PrLinks.parse("https://github.com.attacker.io/team/api/pull/5"))
    }

    @Test fun `filters apply on an enterprise host too`() {
        val text = "https://ghe.local/acme/api/pull/1 https://ghe.local/acme/docs/pull/2"
        assertEquals(listOf("acme/api#1"), PrLinks.find(text, listOf("acme"), listOf("docs"), "ghe.local").map { it.key })
    }
}
