package dev.pocketreviewer.service

import dev.pocketreviewer.service.ManualFlow.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManualFlowTest {

    @Test
    fun review_clean_goesToApprove() {
        assertEquals(Stage.APPROVE, ManualFlow.nextStage(Stage.REVIEW, "APPROVE", hasChatContext = true))
        assertEquals(Stage.APPROVE, ManualFlow.nextStage(Stage.REVIEW, "APPROVE", hasChatContext = false))
    }

    @Test
    fun review_withIssues_goesToPostGithub() {
        assertEquals(Stage.POST_GITHUB, ManualFlow.nextStage(Stage.REVIEW, "COMMENT", hasChatContext = true))
        assertEquals(Stage.POST_GITHUB, ManualFlow.nextStage(Stage.REVIEW, "REQUEST_CHANGES", hasChatContext = false))
    }

    @Test
    fun approve_goesToMerge_thenDone() {
        assertEquals(Stage.MERGE, ManualFlow.nextStage(Stage.APPROVE, "APPROVE", hasChatContext = true))
        assertEquals(Stage.DONE, ManualFlow.nextStage(Stage.MERGE, "APPROVE", hasChatContext = true))
    }

    @Test
    fun postGithub_repliesOnlyWhenChatContext() {
        assertEquals(Stage.CHAT_REPLY, ManualFlow.nextStage(Stage.POST_GITHUB, "COMMENT", hasChatContext = true))
        assertEquals(Stage.DONE, ManualFlow.nextStage(Stage.POST_GITHUB, "COMMENT", hasChatContext = false))
        assertEquals(Stage.DONE, ManualFlow.nextStage(Stage.CHAT_REPLY, "COMMENT", hasChatContext = true))
    }

    @Test
    fun parseStage_defaultsToReviewOnNullOrJunk() {
        assertEquals(Stage.REVIEW, ManualFlow.parseStage(null))
        assertEquals(Stage.REVIEW, ManualFlow.parseStage("nonsense"))
        assertEquals(Stage.MERGE, ManualFlow.parseStage("MERGE"))
    }

    @Test
    fun reactionTarget_prefersResolvedThenChatThenNull() {
        // The missing-approve-emoji bug: chat messageName is null but a resolved name exists.
        assertEquals("spaces/s/messages/m", ManualFlow.reactionTarget("spaces/s/messages/m", null))
        // Fall back to the notification's message name when nothing resolved yet.
        assertEquals("spaces/s/messages/c", ManualFlow.reactionTarget(null, "spaces/s/messages/c"))
        // Nothing to react on.
        assertNull(ManualFlow.reactionTarget(null, null))
    }
}
