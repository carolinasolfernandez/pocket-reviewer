package dev.pocketreviewer.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** spaceOfMessage is the pure piece of the chime-safe reply: derive the real space
 *  from the resolved message name so replies don't post to the bogus notification space. */
class ChatReplyTargetTest {

    @Test fun extractsSpaceFromMessageName() {
        assertEquals("spaces/AAA", GoogleChatClient.spaceOfMessage("spaces/AAA/messages/BBB"))
    }

    @Test fun extractsSpaceWhenMessageIdHasDotsAndDashes() {
        assertEquals("spaces/AAA", GoogleChatClient.spaceOfMessage("spaces/AAA/messages/B-B.C_D"))
    }

    @Test fun trimsWhitespace() {
        assertEquals("spaces/AAA", GoogleChatClient.spaceOfMessage("  spaces/AAA/messages/BBB  "))
    }

    @Test fun returnsNullForReactionName() {
        // A reaction resource name is not a bare message name.
        assertNull(GoogleChatClient.spaceOfMessage("spaces/AAA/messages/BBB/reactions/CCC"))
    }

    @Test fun returnsNullForSpaceOnly() {
        assertNull(GoogleChatClient.spaceOfMessage("spaces/AAA"))
    }

    @Test fun returnsNullForBlank() {
        assertNull(GoogleChatClient.spaceOfMessage(""))
    }
}
