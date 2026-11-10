package dev.pocketreviewer.service

import dev.pocketreviewer.config.AppConfig

/**
 * Decides which app a notification comes from and whether the user wants it
 * processed. Pure (no Android) → unit-testable.
 */
object NotificationSources {

    enum class Source { GOOGLE_CHAT, SLACK, OTHER }

    /**
     * Google Chat ships as its own app (`com.google.android.apps.dynamite`) or embedded
     * in Gmail (`com.google.android.gm`, common on Workspace/Samsung devices).
     */
    fun classify(packageName: String): Source {
        val pkg = packageName.lowercase()
        return when {
            pkg == "com.slack" || pkg.startsWith("com.slack.") -> Source.SLACK
            pkg.contains("chat.google") || pkg.contains("google.chat") ||
                pkg == "com.google.android.gm" || pkg.contains("dynamite") -> Source.GOOGLE_CHAT
            else -> Source.OTHER
        }
    }

    fun isEnabled(source: Source, config: AppConfig): Boolean = when (source) {
        Source.GOOGLE_CHAT -> config.sourceGoogleChat
        Source.SLACK -> config.sourceSlack
        Source.OTHER -> config.sourceOtherApps
    }

    /** The conversation allowlist that applies to [source] (empty = everything). */
    fun allowlist(source: Source, config: AppConfig): List<String> = when (source) {
        Source.GOOGLE_CHAT -> config.chatSpaces
        Source.SLACK -> config.slackChannels
        Source.OTHER -> emptyList()
    }

    private fun normalize(s: String): String = s.trim().trimStart('#').lowercase()

    /**
     * True if the notification belongs to a conversation the user listens to.
     * [labels] is everything we know about where the message was posted (notification
     * title, conversation title, sub text, space/channel id). An empty [allowlist]
     * accepts everything; otherwise one of the labels must contain one of the entries
     * (case-insensitive, a leading '#' is ignored).
     */
    fun conversationAllowed(allowlist: List<String>, labels: List<String?>): Boolean {
        val wanted = allowlist.map { normalize(it) }.filter { it.isNotEmpty() }
        if (wanted.isEmpty()) return true
        val known = labels.filterNotNull().map { normalize(it) }.filter { it.isNotEmpty() }
        return known.any { label -> wanted.any { label.contains(it) } }
    }
}
