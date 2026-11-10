package dev.pocketreviewer.api

import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewTextTest {

    @Test fun keepsTextThatAlreadyStartsWithMarker() {
        val raw = "COMMENT\n\nLooks good overall."
        assertEquals("COMMENT\n\nLooks good overall.", ReviewManager.normalizeReviewText(raw))
    }

    @Test fun stripsReasoningPreambleBeforePlainMarker() {
        val raw = "Let me review this PR.\nKey concerns: stuff\n\nCOMMENT\n\nFinding 1: x"
        assertEquals("COMMENT\n\nFinding 1: x", ReviewManager.normalizeReviewText(raw))
    }

    @Test fun normalizesMarkdownMarkerToBareToken() {
        val raw = "Some reasoning\n\n**COMMENT**\n\nThe review body."
        assertEquals("COMMENT\n\nThe review body.", ReviewManager.normalizeReviewText(raw))
    }

    @Test fun handlesRequestChangesMarker() {
        val raw = "reasoning here\n\nREQUEST_CHANGES\n\n[ERROR] foo.py:1 bad"
        assertEquals("REQUEST_CHANGES\n\n[ERROR] foo.py:1 bad", ReviewManager.normalizeReviewText(raw))
    }

    @Test fun picksFirstStandaloneMarkerNotInlineMention() {
        val raw = "I considered REQUEST_CHANGES but no.\n\nAPPROVE\n\nClean PR."
        assertEquals("APPROVE\n\nClean PR.", ReviewManager.normalizeReviewText(raw))
    }

    @Test fun returnsRawWhenNoStandaloneMarker() {
        val raw = "I would not request_changes here, looks fine."
        assertEquals("I would not request_changes here, looks fine.", ReviewManager.normalizeReviewText(raw))
    }

    // Regression (audit finding A): the model often writes the marker INLINE with the
    // summary ("COMMENT: ...") after a reasoning preamble. The old code only stripped a
    // standalone marker line, so the whole "Let me review..." preamble leaked into the
    // posted GitHub review. The inline summary must be kept, the preamble dropped.
    @Test fun stripsReasoningBeforeInlineMarkerWithSummary() {
        val raw = "Let me review this PR carefully.\n\nKey concerns: stuff\n\nCOMMENT: This introduces a sync handler.\n\n**Findings:** x"
        assertEquals("COMMENT: This introduces a sync handler.\n\n**Findings:** x", ReviewManager.normalizeReviewText(raw))
    }

    @Test fun stripsReasoningBeforeInlineRequestChangesWithDash() {
        val raw = "reasoning\n\nREQUEST_CHANGES - blocking issue\n\n[ERROR] a.py:1"
        assertEquals("REQUEST_CHANGES - blocking issue\n\n[ERROR] a.py:1", ReviewManager.normalizeReviewText(raw))
    }

    @Test fun treatsMarkerWithTrailingColonOnlyAsStandalone() {
        val raw = "some preamble\n\nCOMMENT:\n\nbody here"
        assertEquals("COMMENT\n\nbody here", ReviewManager.normalizeReviewText(raw))
    }

    @Test fun doesNotMatchLongerWordStartingWithMarker() {
        // A line STARTING with "APPROVED" (a longer word) must not be taken as the APPROVE
        // marker by the inline-marker branch; the real standalone COMMENT below is the verdict.
        val raw = "reasoning line\nAPPROVED-ready, not a verdict\n\nCOMMENT\n\nreal body"
        assertEquals("COMMENT\n\nreal body", ReviewManager.normalizeReviewText(raw))
    }
}
