package dev.pocketreviewer.messaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiSpecTest {

    @Test fun `literal emoji is not a shortcode`() {
        assertFalse(EmojiSpec.isShortcode("👀"))
        assertTrue(EmojiSpec.isShortcode("eyes"))
        assertTrue(EmojiSpec.isShortcode(":white_check_mark:"))
        assertTrue(EmojiSpec.isShortcode("+1"))
    }

    @Test fun `toUnicode passes literals through and maps known shortcodes`() {
        assertEquals("👀", EmojiSpec.toUnicode("👀"))
        assertEquals("👀", EmojiSpec.toUnicode(":eyes:"))
        assertEquals("✅", EmojiSpec.toUnicode("check"))
        assertEquals("📞", EmojiSpec.toUnicode("telephone-receiver"))
    }

    @Test fun `toUnicode returns null for an unknown shortcode (custom emoji)`() {
        assertNull(EmojiSpec.toUnicode("shipit"))
        assertNull(EmojiSpec.toUnicode("  "))
    }

    @Test fun `toSlackName maps literals and passes shortcodes through`() {
        assertEquals("eyes", EmojiSpec.toSlackName("👀"))
        assertEquals("white_check_mark", EmojiSpec.toSlackName("✅"))
        assertEquals("speech_balloon", EmojiSpec.toSlackName("💬"))
        assertEquals("shipit", EmojiSpec.toSlackName(":shipit:"))
        assertEquals("+1", EmojiSpec.toSlackName("thumbsup"))
    }

    @Test fun `variation selector does not matter`() {
        assertEquals("warning", EmojiSpec.toSlackName("⚠️"))
        assertEquals("warning", EmojiSpec.toSlackName("⚠"))
    }

    @Test fun `unknown literal emoji has no slack name`() {
        assertNull(EmojiSpec.toSlackName("🦄"))
    }

    @Test fun `default reactions resolve on both platforms`() {
        val d = dev.pocketreviewer.config.AppConfig()
        listOf(d.emojiWorking, d.emojiApprove, d.emojiComment).forEach { spec ->
            assertTrue("unicode for $spec", EmojiSpec.toUnicode(spec) != null)
            assertTrue("slack name for $spec", EmojiSpec.toSlackName(spec) != null)
        }
    }
}
