package dev.pocketreviewer.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MergePolicyTest {

    @Test
    fun hasConflict_trueWhenMergeableFalse() {
        assertTrue(MergePolicy.hasConflict(mergeable = false, mergeableState = "clean"))
    }

    @Test
    fun hasConflict_trueWhenStateDirty() {
        assertTrue(MergePolicy.hasConflict(mergeable = null, mergeableState = "dirty"))
        assertTrue(MergePolicy.hasConflict(mergeable = true, mergeableState = "DIRTY"))
    }

    @Test
    fun hasConflict_falseForCleanMergeableTrue() {
        assertFalse(MergePolicy.hasConflict(mergeable = true, mergeableState = "clean"))
    }

    @Test
    fun hasConflict_behindAndBlockedAreNotConflicts() {
        // out-of-date / branch-protection are warnings, not blocking conflicts
        assertFalse(MergePolicy.hasConflict(mergeable = true, mergeableState = "behind"))
        assertFalse(MergePolicy.hasConflict(mergeable = true, mergeableState = "blocked"))
    }

    @Test
    fun hasConflict_nullMergeableIsNotAConflict() {
        // GitHub still computing -> don't false-positive
        assertFalse(MergePolicy.hasConflict(mergeable = null, mergeableState = "unknown"))
        assertFalse(MergePolicy.hasConflict(mergeable = null, mergeableState = null))
    }

    @Test
    fun blocksAutoApproval_onConflict() {
        assertTrue(MergePolicy.blocksAutoApproval(hasConflict = true, ciState = "SUCCESS"))
    }

    @Test
    fun blocksAutoApproval_onCiFailure() {
        assertTrue(MergePolicy.blocksAutoApproval(hasConflict = false, ciState = "FAILURE"))
    }

    @Test
    fun blocksAutoApproval_allowsCleanGreenOrPendingOrNone() {
        assertFalse(MergePolicy.blocksAutoApproval(false, "SUCCESS"))
        assertFalse(MergePolicy.blocksAutoApproval(false, "NONE"))
        assertFalse(MergePolicy.blocksAutoApproval(false, "PENDING"))
        assertFalse(MergePolicy.blocksAutoApproval(false, "UNKNOWN"))
    }
}
