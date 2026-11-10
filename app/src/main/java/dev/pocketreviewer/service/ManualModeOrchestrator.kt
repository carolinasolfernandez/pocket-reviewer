package dev.pocketreviewer.service

import android.content.Context
import dev.pocketreviewer.R
import dev.pocketreviewer.api.ChatContext
import dev.pocketreviewer.api.ChatMessages
import dev.pocketreviewer.messaging.Messaging
import dev.pocketreviewer.api.ReviewManager
import dev.pocketreviewer.data.ReviewEntry
import dev.pocketreviewer.data.ReviewHistoryRepository
import dev.pocketreviewer.service.ManualFlow.Stage
import dev.pocketreviewer.util.PullRequest

/**
 * STATELESS, history-driven step engine for the manual review flow.
 *
 * Each [runStep]/[skipStep] call performs exactly ONE manual step for a PR:
 * the appropriate GitHub action + the Chat reaction on the resolved message name
 * + a [ReviewHistoryRepository] entry update to advance `manualStage`.
 * There is no in-memory state — all progress is read from and written back to
 * the persisted history. [ManualStepService] provides the coroutine context
 * (a foreground service) so steps survive leaving the app.
 */
object ManualModeOrchestrator {

    // ── History-driven step engine (stateless; old stepper members removed in Task 9) ──
    // Each call runs ONE step for a PR: reads the persisted entry, performs the
    // GitHub action + the Chat reaction (always on the RESOLVED message name, so
    // "approved" reaction lands), then advances `manualStage`. ManualStepService provides the
    // coroutine context (a foreground service), so these survive leaving the app.

    private fun entryFor(context: Context, prKey: String): ReviewEntry? =
        ReviewHistoryRepository.load(context).firstOrNull { it.prKey == prKey }

    private fun chatContextOf(entry: ReviewEntry): ChatContext? =
        entry.chatSpaceName?.let { ChatContext(it, entry.chatMessageName, entry.chatThreadName) }

    private suspend fun setRunning(context: Context, prKey: String, running: Boolean) {
        ReviewHistoryRepository.updateLatestByKey(context, prKey) { it.copy(manualRunning = running) }
    }

    /** Runs the [stage] for [prKey], then advances the entry to the next stage. */
    suspend fun runStep(context: Context, prKey: String, stage: Stage) {
        val entry = entryFor(context, prKey) ?: return
        val pr = PullRequest.fromKey(entry.prKey, entry.prUrl)
        val chat = chatContextOf(entry)
        setRunning(context, prKey, true)
        try {
            when (stage) {
                Stage.REVIEW      -> runReview(context, prKey, pr, chat)
                Stage.APPROVE     -> runApprove(context, prKey, pr)
                Stage.MERGE       -> runMerge(context, prKey, pr)
                Stage.POST_GITHUB -> runPost(context, prKey, pr, entry)
                Stage.CHAT_REPLY  -> runReply(context, prKey, pr, entry, chat)
                Stage.DONE        -> setRunning(context, prKey, false)
            }
        } catch (e: ReviewManager.MergeConflictException) {
            chat?.let { c ->
                runCatching {
                    ChatMessages.conflicts(context)?.let { msg ->
                        Messaging.reply(context, entry.resolvedMessageName, c, msg)
                    }
                }
            }
            ReviewHistoryRepository.updateLatestByKey(context, prKey) {
                it.copy(manualRunning = false, errorMessage = context.getString(R.string.notif_error_merge_conflicts))
            }
        } catch (e: Exception) {
            ReviewHistoryRepository.updateLatestByKey(context, prKey) {
                it.copy(manualRunning = false, errorMessage = e.message ?: context.getString(R.string.notif_error_stage, stage.name))
            }
        }
    }

    /** Skips [stage]: finalizes the 👀 reaction the way the gated flow did, advances stage. */
    suspend fun skipStep(context: Context, prKey: String, stage: Stage) {
        val entry = entryFor(context, prKey) ?: return
        val target = ManualFlow.reactionTarget(entry.resolvedMessageName, entry.chatMessageName)
        val hasChat = entry.chatSpaceName != null && ReviewManager.isThreadReplyConfigured(context)
        when (stage) {
            // Pull the working reaction off so the Chat message isn't left "reviewing".
            Stage.REVIEW -> target?.let {
                runCatching { ReviewManager.swapReaction(context, it, entry.activeReactionName, "SKIP") }
            }
            Stage.POST_GITHUB -> target?.let {
                runCatching { ReviewManager.swapReaction(context, it, entry.activeReactionName, entry.verdict) }
            }
            // APPROVE: skipping the approve does NOT stamp the "approved" emoji (AppConfig.emojiApprove
            // = actual merge); we leave the 👀. MERGE / CHAT_REPLY / DONE: nothing to clean up.
            else -> { }
        }
        val next = ManualFlow.nextStage(stage, entry.verdict, hasChat)
        ReviewHistoryRepository.updateLatestByKey(context, prKey) {
            it.copy(manualStage = next.name, manualRunning = false)
        }
    }

    /**
     * Runs APPROVE + MERGE in a single step (the one-click "Approve and merge" button of the
     * staged flow). Approves, merges and — only after the actual merge — leaves the configurable
     * "approved" emoji (AppConfig.emojiApprove), advancing to DONE. Reuses [runApprove] + [runMerge];
     * conflict/error handling is the same as [runStep]. If the merge conflicts, the PR stays approved
     * and the stage stays at MERGE (the user can retry the merge or resolve the conflict by hand).
     */
    suspend fun runApproveMerge(context: Context, prKey: String) {
        val entry = entryFor(context, prKey) ?: return
        val pr = PullRequest.fromKey(entry.prKey, entry.prUrl)
        val chat = chatContextOf(entry)
        setRunning(context, prKey, true)
        try {
            runApprove(context, prKey, pr)
            runMerge(context, prKey, pr)
        } catch (e: ReviewManager.MergeConflictException) {
            chat?.let { c ->
                runCatching {
                    ChatMessages.conflicts(context)?.let { msg ->
                        Messaging.reply(context, entry.resolvedMessageName, c, msg)
                    }
                }
            }
            ReviewHistoryRepository.updateLatestByKey(context, prKey) {
                it.copy(manualRunning = false, errorMessage = context.getString(R.string.notif_error_merge_conflicts))
            }
        } catch (e: Exception) {
            ReviewHistoryRepository.updateLatestByKey(context, prKey) {
                it.copy(manualRunning = false, errorMessage = e.message ?: context.getString(R.string.notif_error_approve_merge))
            }
        }
    }

    // ── Steps ────────────────────────────────────────────────────────────────

    private suspend fun runReview(context: Context, prKey: String, pr: PullRequest, chat: ChatContext?) {
        // 👀 — discovers the real message; capture its name for every later reaction.
        val eyes = ReviewManager.addWorkingReaction(context, pr, chat)
        val resolved = eyes?.let { Messaging.messageOf(it) }
        // analyzeOnly flips PENDING→REVIEWED and writes reviewText; do it BEFORE we
        // stamp the manual fields, since upsertReviewed rebuilds from a fresh entry.
        val result = ReviewManager.analyzeOnly(context, pr)
        // Reply disabled (blank config) → the flow skips the CHAT_REPLY step.
        val next = ManualFlow.nextStage(Stage.REVIEW, result.verdict,
            chat != null && ReviewManager.isThreadReplyConfigured(context))
        ReviewHistoryRepository.updateLatestByKey(context, prKey) {
            it.copy(
                resolvedMessageName = resolved ?: it.resolvedMessageName,
                activeReactionName  = eyes ?: it.activeReactionName,
                manualStage = next.name,
                manualRunning = false
            )
        }
    }

    private suspend fun runApprove(context: Context, prKey: String, pr: PullRequest) {
        ReviewManager.approvePR(context, pr)
        // The "approved" emoji (AppConfig.emojiApprove) signals an ACTUAL MERGE, not an approval: we do NOT
        // touch the reaction here. We leave the 👀 active; runMerge swaps it only after merging. If the user
        // approves but does NOT merge, the 👀 stays (approve-only, no merge reaction) — same as autonomous.
        ReviewHistoryRepository.updateLatestByKey(context, prKey) {
            it.copy(
                wasApproved = true,
                manualStage = Stage.MERGE.name,
                manualRunning = false
            )
        }
    }

    private suspend fun runMerge(context: Context, prKey: String, pr: PullRequest) {
        ReviewManager.mergeOnly(context, pr)   // throws MergeConflictException on conflict
        val entry = entryFor(context, prKey)
        val target = ManualFlow.reactionTarget(entry?.resolvedMessageName, entry?.chatMessageName)
        var gitMerge: String? = null
        if (target != null) {
            // Replace the verdict emoji with the configured "approved" emoji on the resolved message.
            gitMerge = runCatching {
                ReviewManager.swapReaction(context, target, entry?.activeReactionName, "APPROVE")
            }.getOrNull()
        }
        ReviewHistoryRepository.updateLatestByKey(context, prKey) {
            it.copy(
                wasMerged = true,
                activeReactionName = gitMerge ?: it.activeReactionName,
                manualStage = Stage.DONE.name,
                manualRunning = false
            )
        }
    }

    private suspend fun runPost(context: Context, prKey: String, pr: PullRequest, entry: ReviewEntry) {
        ReviewManager.postAnalysis(context, pr, entry.reviewText)
        val target = ManualFlow.reactionTarget(entry.resolvedMessageName, entry.chatMessageName)
        val newReaction = target?.let { ReviewManager.swapReaction(context, it, entry.activeReactionName, entry.verdict) }
        // Reply disabled (blank config) → the flow skips the CHAT_REPLY step.
        val next = ManualFlow.nextStage(Stage.POST_GITHUB, entry.verdict,
            entry.chatSpaceName != null && ReviewManager.isThreadReplyConfigured(context))
        ReviewHistoryRepository.updateLatestByKey(context, prKey) {
            it.copy(
                wasPosted = true,
                activeReactionName = newReaction ?: it.activeReactionName,
                manualStage = next.name,
                manualRunning = false
            )
        }
    }

    private suspend fun runReply(context: Context, prKey: String, pr: PullRequest, entry: ReviewEntry, chat: ChatContext?) {
        var replyFailed = false
        if (chat != null) {
            // null = message disabled (blank config): nothing is sent and it is NOT an error.
            val replyText = ReviewManager.threadReplyText(context, pr.url, entry.isMultiPR)
            if (replyText != null) {
                // Same as the reactions: we reply on the REAL message resolved by the
                // 👀 ("spaces/S/messages/M"); the notification's space (chime format) is not usable
                // as a REST API resource name. We only fall back to chatContext when nothing was resolved.
                replyFailed = !runCatching {
                    Messaging.reply(context, entry.resolvedMessageName, chat, replyText)
                }.getOrDefault(false)
            }
            val threadId = chat.threadName ?: chat.messageName ?: chat.spaceName
            PRNotificationListener.instance?.watchedThreads?.set(threadId, pr)
        }
        ReviewHistoryRepository.updateLatestByKey(context, prKey) {
            it.copy(
                manualStage = Stage.DONE.name,
                manualRunning = false,
                errorMessage = if (replyFailed) context.getString(R.string.notif_error_thread_reply) else it.errorMessage
            )
        }
    }
}
