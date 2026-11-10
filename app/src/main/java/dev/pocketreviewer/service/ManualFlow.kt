package dev.pocketreviewer.service

/**
 * Pure logic for the manual review flow. No Android dependencies → unit-testable.
 * The flow stages mirror what the gated manual flow used to ask before each step.
 */
object ManualFlow {

    enum class Stage { REVIEW, APPROVE, MERGE, POST_GITHUB, CHAT_REPLY, DONE }

    /** Given the [current] stage just completed, returns the next stage awaiting the user. */
    fun nextStage(current: Stage, verdict: String, hasChatContext: Boolean): Stage = when (current) {
        Stage.REVIEW      -> if (verdict == "APPROVE") Stage.APPROVE else Stage.POST_GITHUB
        Stage.APPROVE     -> Stage.MERGE
        Stage.MERGE       -> Stage.DONE
        Stage.POST_GITHUB -> if (hasChatContext) Stage.CHAT_REPLY else Stage.DONE
        Stage.CHAT_REPLY  -> Stage.DONE
        Stage.DONE        -> Stage.DONE
    }

    /** Parses a persisted stage name; null/unknown means the flow hasn't started → REVIEW. */
    fun parseStage(name: String?): Stage =
        Stage.entries.firstOrNull { it.name == name } ?: Stage.REVIEW

    /**
     * The Chat message name every reaction should target. Prefers the message name
     * resolved when 👀 was placed (always valid); falls back to the notification's
     * message name (null for the unified Gmail/Chat format — the missing-merge-reaction bug).
     */
    fun reactionTarget(resolvedMessageName: String?, chatMessageName: String?): String? =
        resolvedMessageName ?: chatMessageName
}
