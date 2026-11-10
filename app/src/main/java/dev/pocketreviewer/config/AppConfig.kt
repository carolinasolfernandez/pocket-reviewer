package dev.pocketreviewer.config

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.pocketreviewer.api.dataStore
import dev.pocketreviewer.util.GitHubHost
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Every user-tunable setting of the app, in one place. Nothing about a specific
 * organization, repo, chat space or channel is hardcoded anywhere else: code that
 * needs a setting reads it from here.
 *
 * Secrets (GitHub token, Anthropic key, Slack token) are NOT part of this class;
 * they live encrypted behind [SecretStore].
 */
data class AppConfig(
    // ── Master switches ──────────────────────────────────────────────────────
    val notificationAnalysis: Boolean = true,
    /**
     * false = manual mode (the user runs each step). Manual is the default on purpose:
     * a fresh install must never approve or merge anything before the user opts in.
     */
    val autonomous: Boolean = false,

    // ── Notification sources ─────────────────────────────────────────────────
    val sourceGoogleChat: Boolean = true,
    val sourceSlack: Boolean = true,
    /** Any other app (detection only: no reactions, no thread replies). */
    val sourceOtherApps: Boolean = false,
    /** Google Chat spaces to listen to (display name or space id). Empty = all. */
    val chatSpaces: List<String> = emptyList(),
    /** Slack channels to listen to (name, with or without '#', or channel id). Empty = all. */
    val slackChannels: List<String> = emptyList(),

    // ── GitHub ───────────────────────────────────────────────────────────────
    /** "github.com", or the host of a GitHub Enterprise Server (e.g. "github.acme.com"). */
    val githubHost: String = GitHubHost.DEFAULT,
    /** GitHub owners (orgs/users) whose PRs are reviewed. Empty = any owner. */
    val allowedOwners: List<String> = emptyList(),
    /** Repos to skip: "repo" (any owner) or "owner/repo". */
    val ignoredRepos: List<String> = emptyList(),
    /** Sibling repos (same owner) checked for an open PR on the same branch. Empty = off. */
    val relatedRepos: List<String> = emptyList(),

    // ── Review behaviour ─────────────────────────────────────────────────────
    val model: String = DEFAULT_MODEL,
    /** The diff is cut to this many characters before it is sent to the model. */
    val maxDiffChars: Int = DEFAULT_MAX_DIFF_CHARS,
    /** Upper bound for the length of the review (the model's `max_tokens`). */
    val maxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,
    /** Language the review is written in. */
    val reviewLanguage: String = DEFAULT_REVIEW_LANGUAGE,
    val mergeMethod: String = DEFAULT_MERGE_METHOD,
    /** Autonomous mode: approve PRs whose verdict is APPROVE. */
    val autoApprove: Boolean = true,
    /** Autonomous mode: merge after approving (requires [autoApprove]). */
    val autoMerge: Boolean = true,
    val delayMinSec: Int = 120,
    val delayMaxSec: Int = 300,
    val ciWaitMinutes: Int = 15,
    /** CI checks whose name contains any of these (case-insensitive) are ignored. */
    val ignoredChecks: List<String> = emptyList(),

    // ── Reactions ────────────────────────────────────────────────────────────
    val emojiWorking: String = DEFAULT_EMOJI_WORKING,
    /** Put on the message once the PR is approved/merged. */
    val emojiApprove: String = DEFAULT_EMOJI_APPROVE,
    /** Put on the message when the review left comments. */
    val emojiComment: String = DEFAULT_EMOJI_COMMENT,
) {
    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
        const val DEFAULT_REVIEW_LANGUAGE = "English"
        const val DEFAULT_MAX_DIFF_CHARS = 24_000
        const val MIN_DIFF_CHARS = 2_000
        const val MAX_DIFF_CHARS = 400_000
        const val DEFAULT_MAX_OUTPUT_TOKENS = 8_192
        const val MIN_OUTPUT_TOKENS = 512
        const val MAX_OUTPUT_TOKENS = 32_000
        const val DEFAULT_MERGE_METHOD = "squash"
        const val DEFAULT_EMOJI_WORKING = "👀"
        const val DEFAULT_EMOJI_APPROVE = "✅"
        const val DEFAULT_EMOJI_COMMENT = "💬"
        val MERGE_METHODS = listOf("squash", "merge", "rebase")
    }
}

/** DataStore key names. Kept as plain strings so they are greppable and documented. */
object Keys {
    const val NOTIFICATION_ANALYSIS = "notification_analysis"   // "enabled" | "disabled"
    const val REVIEW_MODE = "review_mode"                       // "autonomous" | "manual"

    const val SOURCE_GOOGLE_CHAT = "source_google_chat"         // "true" | "false"
    const val SOURCE_SLACK = "source_slack"
    const val SOURCE_OTHER = "source_other_apps"
    const val CHAT_SPACES = "chat_spaces"                       // list
    const val SLACK_CHANNELS = "slack_channels"                 // list

    const val GITHUB_HOST = "github_host"
    const val ALLOWED_OWNERS = "filter_owners"                  // list
    const val IGNORED_REPOS = "filter_ignored"                  // list
    const val RELATED_REPOS = "related_repos"                   // list

    const val MODEL = "review_model"
    const val REVIEW_LANGUAGE = "review_language"
    const val MAX_DIFF_CHARS = "max_diff_chars"
    const val MAX_OUTPUT_TOKENS = "max_output_tokens"
    const val MERGE_METHOD = "merge_method"
    const val AUTO_APPROVE = "auto_approve"
    const val AUTO_MERGE = "auto_merge"
    const val DELAY_MIN_SEC = "delay_min_sec"
    const val DELAY_MAX_SEC = "delay_max_sec"
    const val CI_WAIT_MINUTES = "ci_wait_minutes"
    const val IGNORED_CHECKS = "ci_ignored_checks"              // list

    const val EMOJI_WORKING = "emoji_working"
    const val EMOJI_APPROVE = "emoji_approve"
    const val EMOJI_COMMENT = "emoji_comment"

    // Secrets (stored encrypted, see SecretStore)
    const val GITHUB_TOKEN = "github_token"
    const val ANTHROPIC_KEY = "anthropic_key"
    const val ANTHROPIC_OAUTH_ACCESS = "anthropic_oauth_access"
    const val ANTHROPIC_OAUTH_REFRESH = "anthropic_oauth_refresh"
    const val SLACK_TOKEN = "slack_token"
}

/** Pure parsing helpers (no Android) so they can be unit-tested on the JVM. */
object ConfigParsing {
    /** Splits a stored list (comma or newline separated), trimming blanks and duplicates. */
    fun parseList(raw: String?): List<String> =
        raw.orEmpty().split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    fun joinList(items: List<String>): String =
        items.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(",")

    fun parseBool(raw: String?, default: Boolean): Boolean = when (raw?.trim()?.lowercase()) {
        "true" -> true
        "false" -> false
        else -> default
    }

    fun parseInt(raw: String?, default: Int, min: Int, max: Int): Int =
        (raw?.trim()?.toIntOrNull() ?: default).coerceIn(min, max)

    /** Returns a valid (min, max) delay pair in seconds: both >= 0 and min <= max. */
    fun delayRange(minSec: Int, maxSec: Int): Pair<Int, Int> {
        val lo = minSec.coerceAtLeast(0)
        val hi = maxSec.coerceAtLeast(lo)
        return lo to hi
    }
}

object ConfigStore {

    private fun Preferences.str(name: String): String? = this[stringPreferencesKey(name)]

    fun fromPrefs(p: Preferences): AppConfig {
        val d = AppConfig()
        val (delayMin, delayMax) = ConfigParsing.delayRange(
            ConfigParsing.parseInt(p.str(Keys.DELAY_MIN_SEC), d.delayMinSec, 0, 3600),
            ConfigParsing.parseInt(p.str(Keys.DELAY_MAX_SEC), d.delayMaxSec, 0, 3600)
        )
        return AppConfig(
            notificationAnalysis = p.str(Keys.NOTIFICATION_ANALYSIS) != "disabled",
            autonomous = p.str(Keys.REVIEW_MODE) == "autonomous",
            sourceGoogleChat = ConfigParsing.parseBool(p.str(Keys.SOURCE_GOOGLE_CHAT), d.sourceGoogleChat),
            sourceSlack = ConfigParsing.parseBool(p.str(Keys.SOURCE_SLACK), d.sourceSlack),
            sourceOtherApps = ConfigParsing.parseBool(p.str(Keys.SOURCE_OTHER), d.sourceOtherApps),
            chatSpaces = ConfigParsing.parseList(p.str(Keys.CHAT_SPACES)),
            slackChannels = ConfigParsing.parseList(p.str(Keys.SLACK_CHANNELS)),
            githubHost = GitHubHost.normalize(p.str(Keys.GITHUB_HOST)),
            allowedOwners = ConfigParsing.parseList(p.str(Keys.ALLOWED_OWNERS)),
            ignoredRepos = ConfigParsing.parseList(p.str(Keys.IGNORED_REPOS)),
            relatedRepos = ConfigParsing.parseList(p.str(Keys.RELATED_REPOS)),
            model = p.str(Keys.MODEL)?.trim()?.takeIf { it.isNotEmpty() } ?: d.model,
            reviewLanguage = p.str(Keys.REVIEW_LANGUAGE)?.trim()?.takeIf { it.isNotEmpty() } ?: d.reviewLanguage,
            maxDiffChars = ConfigParsing.parseInt(
                p.str(Keys.MAX_DIFF_CHARS), d.maxDiffChars, AppConfig.MIN_DIFF_CHARS, AppConfig.MAX_DIFF_CHARS
            ),
            maxOutputTokens = ConfigParsing.parseInt(
                p.str(Keys.MAX_OUTPUT_TOKENS), d.maxOutputTokens, AppConfig.MIN_OUTPUT_TOKENS, AppConfig.MAX_OUTPUT_TOKENS
            ),
            mergeMethod = p.str(Keys.MERGE_METHOD)?.takeIf { it in AppConfig.MERGE_METHODS } ?: d.mergeMethod,
            autoApprove = ConfigParsing.parseBool(p.str(Keys.AUTO_APPROVE), d.autoApprove),
            autoMerge = ConfigParsing.parseBool(p.str(Keys.AUTO_MERGE), d.autoMerge),
            delayMinSec = delayMin,
            delayMaxSec = delayMax,
            ciWaitMinutes = ConfigParsing.parseInt(p.str(Keys.CI_WAIT_MINUTES), d.ciWaitMinutes, 0, 120),
            ignoredChecks = ConfigParsing.parseList(p.str(Keys.IGNORED_CHECKS)),
            emojiWorking = p.str(Keys.EMOJI_WORKING)?.takeIf { it.isNotBlank() } ?: d.emojiWorking,
            emojiApprove = p.str(Keys.EMOJI_APPROVE)?.takeIf { it.isNotBlank() } ?: d.emojiApprove,
            emojiComment = p.str(Keys.EMOJI_COMMENT)?.takeIf { it.isNotBlank() } ?: d.emojiComment,
        )
    }

    suspend fun load(context: Context): AppConfig = fromPrefs(context.dataStore.data.first())

    fun flow(context: Context): Flow<AppConfig> = context.dataStore.data.map { fromPrefs(it) }

    suspend fun save(context: Context, c: AppConfig) {
        context.dataStore.edit { p ->
            fun set(name: String, value: String) { p[stringPreferencesKey(name)] = value }
            set(Keys.NOTIFICATION_ANALYSIS, if (c.notificationAnalysis) "enabled" else "disabled")
            set(Keys.REVIEW_MODE, if (c.autonomous) "autonomous" else "manual")
            set(Keys.SOURCE_GOOGLE_CHAT, c.sourceGoogleChat.toString())
            set(Keys.SOURCE_SLACK, c.sourceSlack.toString())
            set(Keys.SOURCE_OTHER, c.sourceOtherApps.toString())
            set(Keys.CHAT_SPACES, ConfigParsing.joinList(c.chatSpaces))
            set(Keys.SLACK_CHANNELS, ConfigParsing.joinList(c.slackChannels))
            set(Keys.GITHUB_HOST, GitHubHost.normalize(c.githubHost))
            set(Keys.ALLOWED_OWNERS, ConfigParsing.joinList(c.allowedOwners))
            set(Keys.IGNORED_REPOS, ConfigParsing.joinList(c.ignoredRepos))
            set(Keys.RELATED_REPOS, ConfigParsing.joinList(c.relatedRepos))
            set(Keys.MODEL, c.model.trim())
            set(Keys.REVIEW_LANGUAGE, c.reviewLanguage.trim())
            set(Keys.MAX_DIFF_CHARS, c.maxDiffChars.toString())
            set(Keys.MAX_OUTPUT_TOKENS, c.maxOutputTokens.toString())
            set(Keys.MERGE_METHOD, c.mergeMethod)
            set(Keys.AUTO_APPROVE, c.autoApprove.toString())
            set(Keys.AUTO_MERGE, c.autoMerge.toString())
            set(Keys.DELAY_MIN_SEC, c.delayMinSec.toString())
            set(Keys.DELAY_MAX_SEC, c.delayMaxSec.toString())
            set(Keys.CI_WAIT_MINUTES, c.ciWaitMinutes.toString())
            set(Keys.IGNORED_CHECKS, ConfigParsing.joinList(c.ignoredChecks))
            set(Keys.EMOJI_WORKING, c.emojiWorking.trim())
            set(Keys.EMOJI_APPROVE, c.emojiApprove.trim())
            set(Keys.EMOJI_COMMENT, c.emojiComment.trim())
        }
    }
}
