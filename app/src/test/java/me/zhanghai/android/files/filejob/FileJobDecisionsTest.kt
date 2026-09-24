package me.zhanghai.android.files.filejob

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
            ErrorDecision.RETRY,
            errorDecision(ErrorResult(FileJobErrorAction.POSITIVE, true), all::skipCopyMoveError)
        )
        assertFalse(all.skipCopyMoveError)
    }

    @Test fun skipAllIsRememberedButDismissalIsNot() {
        val all = ActionAllInfo()
        assertEquals(
            ErrorDecision.SKIP,
            errorDecision(ErrorResult(FileJobErrorAction.CANCELED, true), all::skipCopyMoveError)
        )
        assertFalse(all.skipCopyMoveError)
        assertEquals(
            ErrorDecision.SKIP,
            errorDecision(ErrorResult(FileJobErrorAction.NEGATIVE, true), all::skipCopyMoveError)
        )
        assertTrue(all.skipCopyMoveError)
    }

    @Test fun cancelStopsTheOperation() {
        assertEquals(
            ErrorDecision.CANCEL,
            errorDecision(
                ErrorResult(FileJobErrorAction.NEUTRAL, false),
                ActionAllInfo()::skipCopyMoveError
            )
        )
    }

    @Test fun refusingCanSkipThisOneOrAllOrCancel() {
        val all = ActionAllInfo()
        assertEquals(
            ErrorDecision.SKIP,
            refusalDecision(
                ErrorResult(FileJobErrorAction.POSITIVE, false),
                all::skipCopyMoveIntoItself
            )
        )
        assertFalse(all.skipCopyMoveIntoItself)
        assertEquals(
            ErrorDecision.SKIP,
            refusalDecision(
                ErrorResult(FileJobErrorAction.POSITIVE, true),
                all::skipCopyMoveIntoItself
            )
        )
        assertTrue(all.skipCopyMoveIntoItself)
        assertFalse(all.skipCopyMoveOverItself)
        assertEquals(
            ErrorDecision.SKIP,
            refusalDecision(
                ErrorResult(FileJobErrorAction.CANCELED, true),
                all::skipCopyMoveOverItself
            )
        )
        assertFalse(all.skipCopyMoveOverItself)
        assertEquals(
            ErrorDecision.CANCEL,
            refusalDecision(
                ErrorResult(FileJobErrorAction.NEGATIVE, false),
                all::skipCopyMoveOverItself
            )
        )
    }

    @Test(expected = AssertionError::class)
    fun aRefusalHasNoNeutralButton() {
        refusalDecision(ErrorResult(FileJobErrorAction.NEUTRAL, false), ActionAllInfo()::merge)
    }

    @Test fun aStepThatCannotBeSkippedIsRetriedOrCancelled() {
        assertEquals(
            ErrorDecision.RETRY,
            retryOrCancelDecision(ErrorResult(FileJobErrorAction.POSITIVE, false))
        )
        assertEquals(
            ErrorDecision.CANCEL,
            retryOrCancelDecision(ErrorResult(FileJobErrorAction.NEGATIVE, false))
        )
        assertEquals(
            ErrorDecision.CANCEL,
            retryOrCancelDecision(ErrorResult(FileJobErrorAction.CANCELED, false))
        )
    }

    @Test(expected = AssertionError::class)
    fun aStepThatCannotBeSkippedHasNoNeutralButton() {
        retryOrCancelDecision(ErrorResult(FileJobErrorAction.NEUTRAL, false))
    }

    @Test fun nothingIsRememberedForAConflictAtFirst() {
        assertNull(rememberedConflictDecision(true, ActionAllInfo()))
        assertNull(rememberedConflictDecision(false, ActionAllInfo()))
    }

    @Test fun mergeAndSkipMergeApplyOnlyToDirectories() {
        assertEquals(
            CopyConflictDecision.MERGE,
            rememberedConflictDecision(true, ActionAllInfo(merge = true))
        )
        assertNull(rememberedConflictDecision(false, ActionAllInfo(merge = true)))
        assertEquals(
            CopyConflictDecision.SKIP,
            rememberedConflictDecision(true, ActionAllInfo(skipMerge = true))
        )
        assertNull(rememberedConflictDecision(false, ActionAllInfo(skipMerge = true)))
    }

    @Test fun replaceAndSkipReplaceApplyOnlyToFiles() {
        assertEquals(
            CopyConflictDecision.REPLACE,
            rememberedConflictDecision(false, ActionAllInfo(replace = true))
        )
        assertNull(rememberedConflictDecision(true, ActionAllInfo(replace = true)))
        assertEquals(
            CopyConflictDecision.SKIP,
            rememberedConflictDecision(false, ActionAllInfo(skipReplace = true))
        )
        assertNull(rememberedConflictDecision(true, ActionAllInfo(skipReplace = true)))
    }
}
