package dev.pocketreviewer.service

/**
 * Aggregates per-PR merge outcomes for a single chat message that contains one or more PRs.
 * The "approved" reaction (configurable, [dev.pocketreviewer.config.AppConfig.emojiApprove]) is a MESSAGE-level signal and the rule is: land it only when
 * EVERY PR in the message actually merged. Each PR's autonomous review reports its outcome
 * here; exactly one report — the one that completes the expected set with all-merged — returns
 * [Completion.ALL_MERGED] so the caller lands the reaction exactly once. Thread-safe and
 * idempotent per prKey (first outcome wins), so a defensive fallback report is harmless.
 */
class MergeAggregator(expected: Collection<String>) {

    enum class Completion { PENDING, ALL_MERGED, DONE_NOT_ALL_MERGED }

    private val expected: Set<String> = expected.toSet()
    private val outcomes = HashMap<String, Boolean>()
    private var fired = false

    @Synchronized
    fun record(prKey: String, merged: Boolean): Completion {
        outcomes.putIfAbsent(prKey, merged)          // first outcome wins (idempotent)
        if (expected.isEmpty()) return Completion.DONE_NOT_ALL_MERGED
        if (!outcomes.keys.containsAll(expected)) return Completion.PENDING
        val allMerged = expected.all { outcomes[it] == true }
        return if (allMerged && !fired) {
            fired = true
            Completion.ALL_MERGED
        } else {
            Completion.DONE_NOT_ALL_MERGED
        }
    }
}
