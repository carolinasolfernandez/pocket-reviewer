package dev.pocketreviewer.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigParsingTest {

    @Test fun `parseList splits on commas and newlines, trims and dedupes`() {
        assertEquals(listOf("a", "b", "c"), ConfigParsing.parseList(" a, b\nc ,, a "))
        assertTrue(ConfigParsing.parseList(null).isEmpty())
        assertTrue(ConfigParsing.parseList("  ").isEmpty())
    }

    @Test fun `joinList round trips`() {
        assertEquals("a,b", ConfigParsing.joinList(listOf(" a ", "", "b", "a")))
        assertEquals(listOf("a", "b"), ConfigParsing.parseList(ConfigParsing.joinList(listOf("a", "b"))))
    }

    @Test fun `parseBool falls back to the default`() {
        assertTrue(ConfigParsing.parseBool("true", false))
        assertFalse(ConfigParsing.parseBool("FALSE", true))
        assertTrue(ConfigParsing.parseBool(null, true))
        assertFalse(ConfigParsing.parseBool("garbage", false))
    }

    @Test fun `parseInt clamps and defaults`() {
        assertEquals(15, ConfigParsing.parseInt(null, 15, 0, 120))
        assertEquals(15, ConfigParsing.parseInt("abc", 15, 0, 120))
        assertEquals(120, ConfigParsing.parseInt("999", 15, 0, 120))
        assertEquals(0, ConfigParsing.parseInt("-5", 15, 0, 120))
    }

    @Test fun `delayRange never returns max below min`() {
        assertEquals(120 to 300, ConfigParsing.delayRange(120, 300))
        assertEquals(300 to 300, ConfigParsing.delayRange(300, 120))
        assertEquals(0 to 0, ConfigParsing.delayRange(-1, -1))
    }

    @Test fun `defaults carry no organization specific values`() {
        val d = AppConfig()
        assertTrue(d.allowedOwners.isEmpty())
        assertTrue(d.ignoredRepos.isEmpty())
        assertTrue(d.relatedRepos.isEmpty())
        assertTrue(d.chatSpaces.isEmpty())
        assertTrue(d.slackChannels.isEmpty())
        assertTrue(d.ignoredChecks.isEmpty())
    }

    @Test fun `a fresh install is in manual mode`() {
        // Safety: nothing may be approved or merged before the user opts in.
        assertFalse(AppConfig().autonomous)
    }

    @Test fun `encrypted values are recognised by prefix`() {
        assertTrue(SecretStore.isEncrypted(SecretStore.PREFIX + "abc"))
        assertFalse(SecretStore.isEncrypted("ghp_plaintext"))
        assertFalse(SecretStore.isEncrypted(null))
    }
}
