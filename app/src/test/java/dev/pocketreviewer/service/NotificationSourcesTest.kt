package dev.pocketreviewer.service

import dev.pocketreviewer.config.AppConfig
import dev.pocketreviewer.service.NotificationSources.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationSourcesTest {

    @Test fun `classifies the known packages`() {
        assertEquals(Source.SLACK, NotificationSources.classify("com.Slack"))
        assertEquals(Source.GOOGLE_CHAT, NotificationSources.classify("com.google.android.apps.dynamite"))
        assertEquals(Source.GOOGLE_CHAT, NotificationSources.classify("com.google.android.gm"))
        assertEquals(Source.OTHER, NotificationSources.classify("com.whatsapp"))
        assertEquals(Source.OTHER, NotificationSources.classify("com.slackline.app"))
    }

    @Test fun `sources follow the config switches`() {
        val cfg = AppConfig(sourceGoogleChat = false, sourceSlack = true, sourceOtherApps = false)
        assertFalse(NotificationSources.isEnabled(Source.GOOGLE_CHAT, cfg))
        assertTrue(NotificationSources.isEnabled(Source.SLACK, cfg))
        assertFalse(NotificationSources.isEnabled(Source.OTHER, cfg))
    }

    @Test fun `other apps are off by default`() {
        assertFalse(NotificationSources.isEnabled(Source.OTHER, AppConfig()))
    }

    @Test fun `each source uses its own allowlist`() {
        val cfg = AppConfig(chatSpaces = listOf("Eng"), slackChannels = listOf("#reviews"))
        assertEquals(listOf("Eng"), NotificationSources.allowlist(Source.GOOGLE_CHAT, cfg))
        assertEquals(listOf("#reviews"), NotificationSources.allowlist(Source.SLACK, cfg))
        assertTrue(NotificationSources.allowlist(Source.OTHER, cfg).isEmpty())
    }

    @Test fun `empty allowlist accepts everything`() {
        assertTrue(NotificationSources.conversationAllowed(emptyList(), listOf(null, "anything")))
        assertTrue(NotificationSources.conversationAllowed(listOf("  "), emptyList()))
    }

    @Test fun `matches channel names ignoring case and the hash`() {
        assertTrue(NotificationSources.conversationAllowed(listOf("#Code-Review"), listOf("Ana", "#code-review")))
        assertTrue(NotificationSources.conversationAllowed(listOf("code-review"), listOf("#code-review (Acme)")))
        assertFalse(NotificationSources.conversationAllowed(listOf("code-review"), listOf("Ana", "#random")))
    }

    @Test fun `matches a space or channel id label`() {
        assertTrue(NotificationSources.conversationAllowed(listOf("spaces/AAA"), listOf("Ana", "spaces/AAA")))
        assertTrue(NotificationSources.conversationAllowed(listOf("C0123ABCD"), listOf(null, "slack/C0123ABCD")))
    }

    @Test fun `no labels with a non empty allowlist is rejected`() {
        assertFalse(NotificationSources.conversationAllowed(listOf("eng"), listOf(null, "")))
    }
}
