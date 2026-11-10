package dev.pocketreviewer.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessagesTest {

    // ---- resolve: null=default, blank=disabled ----
    @Test fun `never-saved key falls back to default`() {
        assertEquals("def", ChatMessages.resolve(null, "def"))
    }

    @Test fun `blank stored value disables the message`() {
        assertNull(ChatMessages.resolve("", "def"))
        assertNull(ChatMessages.resolve("   ", "def"))
    }

    @Test fun `stored text wins over default`() {
        assertEquals("custom", ChatMessages.resolve("custom", "def"))
    }

    @Test fun `defaults are non-blank`() {
        assertTrue(ChatMessages.DEFAULT_THREAD_MSG.isNotBlank())
        assertTrue(ChatMessages.DEFAULT_CONFLICTS.isNotBlank())
        assertTrue(ChatMessages.DEFAULT_APPROVE_FAILED.isNotBlank())
    }

    // ---- buildApproveFailed ----
    @Test fun `error is substituted into the template`() {
        assertEquals(
            "⚠️ I couldn't approve the PR: 403 forbidden",
            ChatMessages.buildApproveFailed(ChatMessages.DEFAULT_APPROVE_FAILED, "403 forbidden")
        )
    }

    @Test fun `null or blank error becomes the word error`() {
        assertEquals("failed: error", ChatMessages.buildApproveFailed("failed: {error}", null))
        assertEquals("failed: error", ChatMessages.buildApproveFailed("failed: {error}", "  "))
    }

    @Test fun `long errors are truncated to 200 chars`() {
        val out = ChatMessages.buildApproveFailed("{error}", "x".repeat(500))
        assertEquals(200, out.length)
    }

    @Test fun `template without placeholder is sent as-is`() {
        assertEquals("could not approve", ChatMessages.buildApproveFailed("could not approve", "boom"))
    }

    // ---- ownEchoBases ----
    @Test fun `defaults produce three bases with approve prefix stripped`() {
        assertEquals(
            listOf(ChatMessages.DEFAULT_THREAD_MSG, ChatMessages.DEFAULT_CONFLICTS, "⚠️ I couldn't approve the PR:"),
            ChatMessages.ownEchoBases(null, null, null)
        )
    }

    @Test fun `disabled messages drop out of the bases`() {
        assertEquals(
            listOf(ChatMessages.DEFAULT_CONFLICTS),
            ChatMessages.ownEchoBases("", null, "")
        )
    }

    @Test fun `placeholder-leading template contributes no base`() {
        // "{error} while approving" → prefix "" → excluded (an empty base would match everything)
        assertEquals(
            listOf(ChatMessages.DEFAULT_THREAD_MSG, ChatMessages.DEFAULT_CONFLICTS),
            ChatMessages.ownEchoBases(null, null, "{error} while approving")
        )
    }

    @Test fun `custom texts flow into the bases`() {
        assertEquals(
            listOf("reviewed 👀", "has conflicts", "not approved:"),
            ChatMessages.ownEchoBases("reviewed 👀", "has conflicts", "not approved: {error}")
        )
    }
}
