package dev.pocketreviewer.messaging

import dev.pocketreviewer.api.GoogleChatClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SlackNamesTest {

    @Test fun `builds and parses message names`() {
        val name = SlackNames.message("C0123ABCD", "1700000000.000100")
        assertEquals("slack/C0123ABCD/messages/1700000000.000100", name)
        assertEquals(SlackNames.MessageRef("C0123ABCD", "1700000000.000100"), SlackNames.parseMessage(name))
        assertNull(SlackNames.parseMessage("spaces/S/messages/M"))
    }

    @Test fun `reaction name carries the message, and messageOf strips it`() {
        val reaction = SlackNames.reaction("C0123ABCD", "1.2", "eyes")
        assertEquals(SlackNames.ReactionRef("C0123ABCD", "1.2", "eyes"), SlackNames.parseReaction(reaction))
        assertEquals("slack/C0123ABCD/messages/1.2", Messaging.messageOf(reaction))
        assertNull(SlackNames.parseReaction("slack/C0123ABCD/messages/1.2"))
    }

    @Test fun `messageOf leaves a bare message name untouched`() {
        assertEquals("spaces/S/messages/M", Messaging.messageOf("spaces/S/messages/M"))
        assertEquals("spaces/S/messages/M", Messaging.messageOf("spaces/S/messages/M/reactions/R"))
    }

    @Test fun `space with unknown channel has no channel id`() {
        assertEquals("slack/unknown", SlackNames.space(null))
        assertNull(SlackNames.channelOfSpace(SlackNames.space(null)))
        assertEquals("C0123ABCD", SlackNames.channelOfSpace(SlackNames.space("C0123ABCD")))
        assertNull(SlackNames.channelOfSpace("spaces/AAA"))
    }

    @Test fun `thread ts is parsed`() {
        assertEquals("1.2", SlackNames.parseThreadTs(SlackNames.thread("C0123ABCD", "1.2")))
        assertNull(SlackNames.parseThreadTs("slack/C0123ABCD"))
    }

    @Test fun `channel id is extracted from notification identifiers`() {
        assertEquals("C0123ABCD", SlackNames.channelIdFrom(listOf(null, "T999:C0123ABCD:thread")))
        assertEquals("G01ABCDEFGH", SlackNames.channelIdFrom(listOf("noise", "G01ABCDEFGH")))
        assertEquals("D0123ABCD", SlackNames.channelIdFrom(listOf("0|com.Slack|123|D0123ABCD|10")))
    }

    @Test fun `no id shaped token means unknown channel`() {
        assertNull(SlackNames.channelIdFrom(listOf(null, "com.Slack", "general", "0|com.Slack|123|null|10")))
        // must not pick an id out of the middle of a longer word
        assertNull(SlackNames.channelIdFrom(listOf("XC0123ABCDEFGHIJKLMNOP")))
    }

    @Test fun `routing picks the provider by resource name`() {
        assertSame(SlackClient, Messaging.providerFor("slack/C0123ABCD/messages/1.2"))
        assertSame(SlackClient, Messaging.providerFor("slack/unknown"))
        assertSame(GoogleChatClient, Messaging.providerFor("spaces/AAA/messages/BBB"))
        assertSame(GoogleChatClient, Messaging.providerFor(null))
        assertTrue(SlackNames.owns("slack/x"))
        assertFalse(SlackNames.owns("slackish/x"))
    }
}
