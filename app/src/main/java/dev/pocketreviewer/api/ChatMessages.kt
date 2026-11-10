package dev.pocketreviewer.api

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * Single catalog of the TEXTS the app writes to the chat (Google Chat / Slack). Each
 * message is a per-user configurable template (DataStore, edited from Settings).
 *
 * Resolution rule: key NEVER saved → default; saved BLANK → null = message disabled
 * (nothing is written to the chat). The builders are pure (no Android) so they can
 * be unit-tested, like MergePolicy/AutonomousDispatch.
 */
object ChatMessages {

    const val KEY_THREAD_MSG = "thread_msg"                      // "I left comments" reply (pre-existing key)
    const val KEY_CONFLICTS = "chat_msg_conflicts"
    const val KEY_APPROVE_FAILED = "chat_msg_approve_failed"

    const val DEFAULT_THREAD_MSG = "I left some comments on the PR"
    const val DEFAULT_CONFLICTS =
        "⚠️ This PR has merge conflicts and can't be merged until they are resolved."
    const val DEFAULT_APPROVE_FAILED = "⚠️ I couldn't approve the PR: {error}"

    const val ERROR_PLACEHOLDER = "{error}"
    private const val MAX_ERROR_LEN = 200

    /** null (never saved) → [default]; blank → null (disabled); otherwise the stored value. */
    internal fun resolve(stored: String?, default: String): String? = when {
        stored == null   -> default
        stored.isBlank() -> null
        else             -> stored
    }

    /** Replaces {error} with the truncated error (or "error"). No placeholder → template as is. */
    internal fun buildApproveFailed(template: String, error: String?): String {
        val detail = error?.takeIf { it.isNotBlank() }?.take(MAX_ERROR_LEN) ?: "error"
        return template.replace(ERROR_PLACEHOLDER, detail)
    }

    /**
     * Bases used to recognize the ECHO of one of our own messages coming back as a
     * notification (see AutonomousDispatch.isOwnThreadReply). From the approve-failed
     * template only the prefix before {error} is usable (the error is expanded when
     * sending). Disabled messages or ones with an empty prefix are left out: an empty
     * base would match everything.
     */
    internal fun ownEchoBases(
        storedThreadMsg: String?,
        storedConflicts: String?,
        storedApproveFailed: String?
    ): List<String> {
        val thread = resolve(storedThreadMsg, DEFAULT_THREAD_MSG)
        val conflicts = resolve(storedConflicts, DEFAULT_CONFLICTS)
        val approvePrefix = resolve(storedApproveFailed, DEFAULT_APPROVE_FAILED)
            ?.substringBefore(ERROR_PLACEHOLDER)?.trim()
        return listOfNotNull(thread, conflicts, approvePrefix).filter { it.isNotBlank() }
    }

    /** Merge-conflicts text, or null if it is disabled. */
    suspend fun conflicts(context: Context): String? {
        val prefs = context.dataStore.data.first()
        return resolve(prefs[stringPreferencesKey(KEY_CONFLICTS)], DEFAULT_CONFLICTS)
    }

    /** "Couldn't approve" text with {error} already substituted, or null if it is disabled. */
    suspend fun approveFailed(context: Context, error: String?): String? {
        val prefs = context.dataStore.data.first()
        val template = resolve(prefs[stringPreferencesKey(KEY_APPROVE_FAILED)], DEFAULT_APPROVE_FAILED)
            ?: return null
        return buildApproveFailed(template, error)
    }
}
