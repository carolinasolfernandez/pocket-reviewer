package dev.pocketreviewer.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ReviewEntryCompatTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun oldJsonWithoutNewFields_loadsWithDefaults() {
        // A persisted entry written before the manual-flow fields existed.
        val old = """
            {"prKey":"acme/x#1","prUrl":"https://github.com/acme/x/pull/1",
             "verdict":"APPROVE","reviewText":"ok","timestampMs":123}
        """.trimIndent()

        val entry = json.decodeFromString(ReviewEntry.serializer(), old)

        assertEquals("acme/x#1", entry.prKey)
        assertNull(entry.resolvedMessageName)
        assertNull(entry.activeReactionName)
        assertNull(entry.manualStage)
        assertFalse(entry.manualRunning)
    }

    @Test
    fun newFieldsRoundTrip() {
        val entry = ReviewEntry(
            prKey = "acme/x#2", prUrl = "u", verdict = "APPROVE",
            reviewText = "r", timestampMs = 1,
            resolvedMessageName = "spaces/s/messages/m",
            activeReactionName = "spaces/s/messages/m/reactions/r",
            manualStage = "MERGE", manualRunning = true
        )
        val round = json.decodeFromString(ReviewEntry.serializer(), json.encodeToString(ReviewEntry.serializer(), entry))
        assertEquals("spaces/s/messages/m", round.resolvedMessageName)
        assertEquals("MERGE", round.manualStage)
        assertEquals(true, round.manualRunning)
    }
}
