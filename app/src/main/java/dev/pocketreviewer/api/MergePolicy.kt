package dev.pocketreviewer.api

/**
 * Pure decision logic for merge-conflict / CI gating, kept free of Android deps so
 * it can be unit-tested directly.
 *
 * Policy (confirmed with the user):
 *  - A *blocking* conflict is a real file-level conflict only: GitHub `mergeable == false`
 *    or `mergeable_state == "dirty"`. `behind` / `blocked` / `unknown` are NOT blockers
 *    (they're surfaced as warnings elsewhere, not gates).
 *  - In autonomous mode, approve+merge is withheld when there's a conflict OR CI failed.
 */
object MergePolicy {

    /** True only for a real merge conflict. `mergeable == null` means GitHub is still
     *  computing it — treated as "not a conflict" to avoid false positives. */
    fun hasConflict(mergeable: Boolean?, mergeableState: String?): Boolean =
        mergeable == false || mergeableState?.equals("dirty", ignoreCase = true) == true

    /** Autonomous mode must neither approve nor merge when conflicted or CI is red. */
    fun blocksAutoApproval(hasConflict: Boolean, ciState: String): Boolean =
        hasConflict || ciState == "FAILURE"
}
