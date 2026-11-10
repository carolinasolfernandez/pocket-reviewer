package dev.pocketreviewer.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression tests for the 👀-resolution bug: re-reviewing the same PR made the API
 * return 409 (reaction already exists); the old code treated that as failure and
 * discarded the resolved message name, so the verdict swap and the approve emoji had no
 * target and silently no-op'd. Once the message is found, resolution must NEVER be
 * lost to a reaction hiccup.
 */
class ReactionResolutionTest {

    @Test fun `message not found in this space returns null so the search continues`() {
        assertNull(GoogleChatClient.resolveReactionHandle(null, 200, "x", null))
        assertNull(GoogleChatClient.resolveReactionHandle(null, 409, null, "x"))
    }

    @Test fun `successful reaction returns the new reaction name`() {
        assertEquals(
            "spaces/S/messages/M/reactions/R",
            GoogleChatClient.resolveReactionHandle("spaces/S/messages/M", 200, "spaces/S/messages/M/reactions/R", null)
        )
        assertEquals(
            "spaces/S/messages/M/reactions/R",
            GoogleChatClient.resolveReactionHandle("spaces/S/messages/M", 201, "spaces/S/messages/M/reactions/R", null)
        )
    }

    @Test fun `success without a reaction name falls back to the message name`() {
        assertEquals(
            "spaces/S/messages/M",
            GoogleChatClient.resolveReactionHandle("spaces/S/messages/M", 200, null, null)
        )
    }

    // THE regression: 👀 already on the message (re-review) → 409. Must keep the target.
    @Test fun `409 already-exists reuses the existing reaction name`() {
        assertEquals(
            "spaces/S/messages/M/reactions/OLD",
            GoogleChatClient.resolveReactionHandle("spaces/S/messages/M", 409, null, "spaces/S/messages/M/reactions/OLD")
        )
    }

    @Test fun `409 without a findable reaction still resolves to the message name`() {
        assertEquals(
            "spaces/S/messages/M",
            GoogleChatClient.resolveReactionHandle("spaces/S/messages/M", 409, null, null)
        )
    }

    // Other reaction errors (500 / 403): reaction failed but the message is resolved —
    // return the message name so targeting still works (a later remove no-ops safely).
    @Test fun `other reaction errors still resolve to the message name`() {
        assertEquals(
            "spaces/S/messages/M",
            GoogleChatClient.resolveReactionHandle("spaces/S/messages/M", 500, null, null)
        )
        assertEquals(
            "spaces/S/messages/M",
            GoogleChatClient.resolveReactionHandle("spaces/S/messages/M", 403, null, null)
        )
    }
}
