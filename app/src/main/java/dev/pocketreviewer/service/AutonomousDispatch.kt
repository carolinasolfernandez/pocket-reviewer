package dev.pocketreviewer.service

import dev.pocketreviewer.data.ReviewEntry
import dev.pocketreviewer.util.PullRequest

/**
 * Pure decision helpers for the autonomous notification dispatch. Kept free of Android
 * deps so they can be unit-tested directly (mirrors MergePolicy).
 */
object AutonomousDispatch {

    /**
     * The text of the JUST-ARRIVED chat message. Google Chat MessagingStyle notifications
     * accumulate recent messages, so scanning the whole grouped body re-detects a PREVIOUS
     * message's PR link. [messageTexts] is the grouped list oldest→newest; the newest
     * non-blank entry is the new message. Falls back to [text] (collapsed latest line) then
     * [bigText] when no grouped messages are present.
     */
    fun newestMessageText(messageTexts: List<String>, text: String, bigText: String): String {
        messageTexts.lastOrNull { it.isNotBlank() }?.let { return it }
        if (text.isNotBlank()) return text
        return bigText
    }

    /**
     * Whether to skip auto-(re)reviewing [prKey]. Skips when a review for this PR is already
     * in flight ([inFlight]) or when it has already been merged by us (history [wasMerged]).
     * A PR that was reviewed but NOT merged is intentionally re-reviewable (e.g. re-sent).
     */
    fun shouldSkipAutonomousReview(
        prKey: String,
        history: List<ReviewEntry>,
        inFlight: Set<String>
    ): Boolean =
        prKey in inFlight || history.any { it.prKey == prKey && it.wasMerged }

    /**
     * Whether [messageText] is (very likely) the app's OWN thread reply echoed back in a
     * notification. Our reply is "<base>" or "<base> — <prUrl>" (see ReviewManager.buildThreadReply),
     * so a message whose trimmed text starts with the configured [replyBase] is treated as ours and
     * must NOT re-trigger a review. A blank [replyBase] never matches (avoids skipping everything).
     */
    fun isOwnThreadReply(messageText: String, replyBase: String): Boolean {
        val base = replyBase.trim()
        if (base.isEmpty()) return false
        return messageText.trim().startsWith(base)
    }

    /**
     * Multi-base variant: the message is ours if it matches ANY configured base
     * (thread reply, conflicts, approve-failed — see ChatMessages.ownEchoBases).
     */
    fun isOwnThreadReply(messageText: String, replyBases: List<String>): Boolean =
        replyBases.any { isOwnThreadReply(messageText, it) }

    /**
     * Resolves which watched PR a thread-reply notification belongs to. [watchedThreads] is keyed
     * by whatever id was available at registration time — thread name, message name, or space name
     * (see ReviewWorker / ManualModeOrchestrator). The reply notification may carry a DIFFERENT
     * thread/message id than the one registered, so an exact [threadId] hit wins but an unwatched
     * (or null) [threadId] must still fall back to the space-level entry.
     */
    fun resolveWatchedPR(
        threadId: String?,
        spaceName: String?,
        watchedThreads: Map<String, PullRequest>
    ): PullRequest? =
        threadId?.let { watchedThreads[it] } ?: spaceName?.let { watchedThreads[it] }
}
