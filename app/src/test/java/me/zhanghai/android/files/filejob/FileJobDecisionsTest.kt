package me.zhanghai.android.files.filejob

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileJobDecisionsTest {
    @Test fun mergeReplaceAndSkipPoliciesStayIndependent() {
        val all = ActionAllInfo()
        val replace = ConflictResult(FileJobConflictAction.MERGE_OR_REPLACE, null, true)
        assertEquals(CopyConflictDecision.MERGE, copyConflictDecision(replace, true, all))
        assertTrue(all.merge)
        assertFalse(all.replace)
        assertEquals(CopyConflictDecision.REPLACE, copyConflictDecision(replace, false, all))
        assertTrue(all.replace)
        assertEquals(
            CopyConflictDecision.SKIP,
            copyConflictDecision(ConflictResult(FileJobConflictAction.SKIP, null, true), false, all)
        )
        assertTrue(all.skipReplace)
        assertFalse(all.skipMerge)
        assertEquals(
            CopyConflictDecision.CANCEL,
            copyConflictDecision(
                ConflictResult(FileJobConflictAction.CANCEL, null, false),
                true,
                all
            )
        )
    }

    @Test fun retryDoesNotChangeSkipAllPolicy() {
        val all = ActionAllInfo()
        assertEquals(
            CopyErrorDecision.RETRY,
            copyErrorDecision(ErrorResult(FileJobErrorAction.POSITIVE, true), all)
        )
        assertFalse(all.skipCopyMoveError)
    }

    @Test fun skipAllIsRememberedButDismissalIsNot() {
        val all = ActionAllInfo()
        assertEquals(
            CopyErrorDecision.SKIP,
            copyErrorDecision(ErrorResult(FileJobErrorAction.CANCELED, true), all)
        )
        assertFalse(all.skipCopyMoveError)
        assertEquals(
            CopyErrorDecision.SKIP,
            copyErrorDecision(ErrorResult(FileJobErrorAction.NEGATIVE, true), all)
        )
        assertTrue(all.skipCopyMoveError)
    }

    @Test fun cancelStopsTheOperation() {
        assertEquals(
            CopyErrorDecision.CANCEL,
            copyErrorDecision(ErrorResult(FileJobErrorAction.NEUTRAL, false), ActionAllInfo())
        )
    }
}
