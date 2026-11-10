package dev.pocketreviewer.api

import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadReplyTest {

    @Test fun singlePrUsesBaseMessageOnly() {
        assertEquals(
            "left comments",
            ReviewManager.buildThreadReply("left comments", "https://github.com/o/r/pull/1", false)
        )
    }

    @Test fun multiPrAppendsUrl() {
        assertEquals(
            "left comments — https://github.com/o/r/pull/1",
            ReviewManager.buildThreadReply("left comments", "https://github.com/o/r/pull/1", true)
        )
    }

    @Test fun respectsCustomBaseMessage() {
        assertEquals(
            "reviewed 👀",
            ReviewManager.buildThreadReply("reviewed 👀", "https://github.com/o/r/pull/1", false)
        )
    }
}
