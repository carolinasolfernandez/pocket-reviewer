package dev.pocketreviewer.messaging

/**
 * An emoji setting is a free-text "spec": either a literal unicode emoji ("👀") or a
 * shortcode ("eyes", "white_check_mark", or the name of a workspace custom emoji such
 * as "shipit"), with or without surrounding colons.
 *
 * Google Chat needs the unicode character (or a custom-emoji resource); Slack needs
 * the shortcode. This object converts between the two. Pure → unit-testable.
 */
object EmojiSpec {

    private val PAIRS = listOf(
        "eyes" to "👀",
        "white_check_mark" to "✅",
        "heavy_check_mark" to "✔️",
        "x" to "❌",
        "negative_squared_cross_mark" to "❎",
        "speech_balloon" to "💬",
        "memo" to "📝",
        "warning" to "⚠️",
        "telephone_receiver" to "📞",
        "tada" to "🎉",
        "rocket" to "🚀",
        "+1" to "👍",
        "-1" to "👎",
        "ok_hand" to "👌",
        "raised_hands" to "🙌",
        "100" to "💯",
        "fire" to "🔥",
        "exclamation" to "❗",
        "question" to "❓",
        "hourglass_flowing_sand" to "⏳",
        "robot_face" to "🤖",
        "twisted_rightwards_arrows" to "🔀",
        "large_green_circle" to "🟢",
        "red_circle" to "🔴",
        "construction" to "🚧",
        "pray" to "🙏",
        "thinking_face" to "🤔",
        "mag" to "🔍",
        "sparkles" to "✨",
        "bug" to "🐛",
    )

    // Aliases people commonly type that are not the canonical Slack name.
    private val ALIASES = mapOf(
        "check" to "white_check_mark",
        "thumbsup" to "+1",
        "thumbsdown" to "-1",
        "phone" to "telephone_receiver",
        "telephone-receiver" to "telephone_receiver",
        "party" to "tada",
    )

    private val SHORTCODE_TO_UNICODE: Map<String, String> = PAIRS.toMap()
    // Variation selectors (U+FE0F) are stripped so "⚠" and "⚠️" both match.
    private val UNICODE_TO_SHORTCODE: Map<String, String> =
        PAIRS.associate { (code, emoji) -> stripVariation(emoji) to code }

    private fun stripVariation(s: String) = s.replace("️", "")

    /** Trims whitespace and surrounding colons. */
    fun normalize(spec: String): String = spec.trim().trim(':')

    /** True if [spec] is a shortcode (ASCII name) rather than a literal emoji. */
    fun isShortcode(spec: String): Boolean = normalize(spec).matches(Regex("^[A-Za-z0-9_+-]+$"))

    private fun canonical(shortcode: String): String {
        val s = shortcode.lowercase()
        return ALIASES[s] ?: s
    }

    /**
     * Unicode character for [spec], or null when it is a shortcode we don't know
     * (i.e. probably a workspace custom emoji).
     */
    fun toUnicode(spec: String): String? {
        val s = normalize(spec)
        if (s.isEmpty()) return null
        return if (isShortcode(s)) SHORTCODE_TO_UNICODE[canonical(s)] else s
    }

    /**
     * Slack reaction name for [spec]. Shortcodes pass through (custom emoji work by
     * name in Slack); literal emoji are mapped, null if we don't know the name.
     */
    fun toSlackName(spec: String): String? {
        val s = normalize(spec)
        if (s.isEmpty()) return null
        return if (isShortcode(s)) canonical(s) else UNICODE_TO_SHORTCODE[stripVariation(s)]
    }
}
