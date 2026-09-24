/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import android.content.Context
import java.io.IOException
import java.io.InterruptedIOException
import java8.nio.file.Path
import kotlin.coroutines.Continuation
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.provider.common.TestPath
import me.zhanghai.android.files.provider.common.UserAction
import me.zhanghai.android.files.provider.common.UserActionRequiredException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FileJobRetryTest {
    private val job = object : FileJob() {
        override fun run() {
            // The job is only a receiver for the helpers under test.
        }
    }

    private val decisions = ScriptedDecisions().also { job.decisions = it }

    @Test
    fun aStepThatWorksAtOnceIsNeverDecidedOn() {
        var attempts = 0
        val isDone =
            retryUntilDecided({ ++attempts }) {
                fail("decided on $it")
                ErrorDecision.SKIP
            }
        assertTrue(isDone)
        assertEquals(1, attempts)
    }

    @Test
    fun aStepIsRetriedUntilItWorks() {
        var attempts = 0
        val errors = mutableListOf<IOException>()
        val isDone = retryUntilDecided({ if (++attempts < 3) throw IOException("try $attempts") }) {
            errors += it
            ErrorDecision.RETRY
        }
        assertTrue(isDone)
        assertEquals(3, attempts)
        assertEquals(listOf("try 1", "try 2"), errors.map { it.message })
    }

    @Test
    fun aSkippedStepReportsThatItWasNotDone() {
        assertFalse(retryUntilDecided({ throw IOException() }) { ErrorDecision.SKIP })
    }

    @Test(expected = InterruptedIOException::class)
    fun cancellingAStepCancelsTheJob() {
        retryUntilDecided({ throw IOException() }) { ErrorDecision.CANCEL }
    }

    @Test
    fun aJobCancelledDuringAStepIsNotAskedAbout() {
        val cancellation = InterruptedIOException()
        try {
            retryUntilDecided({ throw cancellation }) {
                fail("decided on $it")
                ErrorDecision.RETRY
            }
            fail("cancellation was swallowed")
        } catch (e: InterruptedIOException) {
            assertSame(cancellation, e)
        }
    }

    @Test
    fun skipAllSkipsWithoutAskingAndCountsTheError() {
        val all = ActionAllInfo(skipDeleteError = true)
        val decision = job.decideOnError(IOException(), all::skipDeleteError) { unexpectedDialog() }
        assertEquals(ErrorDecision.SKIP, decision)
        assertEquals(1, job.skippedErrorCount)
    }

    @Test
    fun aCompletedUserActionRetriesWithoutAsking() {
        decisions.userActionResult = true
        val exception = TestUserActionRequiredException()
        val all = ActionAllInfo()
        val decision = job.decideOnError(exception, all::skipDeleteError) { unexpectedDialog() }
        assertEquals(ErrorDecision.RETRY, decision)
        assertEquals(listOf(exception), decisions.userActions)
    }

    @Test
    fun aDeclinedUserActionFallsBackToTheDialog() {
        decisions.userActionResult = false
        val all = ActionAllInfo()
        val decision = job.decideOnError(TestUserActionRequiredException(), all::skipDeleteError) {
            ErrorResult(FileJobErrorAction.POSITIVE, false)
        }
        assertEquals(ErrorDecision.RETRY, decision)
        assertEquals(1, decisions.userActions.size)
    }

    @Test
    fun skippingInTheDialogCountsTheErrorAndCanApplyToAll() {
        val all = ActionAllInfo()
        val decision = job.decideOnError(IOException(), all::skipSetModeError) {
            ErrorResult(FileJobErrorAction.NEGATIVE, true)
        }
        assertEquals(ErrorDecision.SKIP, decision)
        assertTrue(all.skipSetModeError)
        assertEquals(1, job.skippedErrorCount)
    }

    @Test
    fun dismissingTheDialogSkipsWithoutCountingAnError() {
        val all = ActionAllInfo()
        val decision = job.decideOnError(IOException(), all::skipSetModeError) {
            ErrorResult(FileJobErrorAction.CANCELED, true)
        }
        assertEquals(ErrorDecision.SKIP, decision)
        assertFalse(all.skipSetModeError)
        assertEquals(0, job.skippedErrorCount)
    }

    @Test
    fun cancelInTheDialogCancels() {
        val all = ActionAllInfo()
        val decision = job.decideOnError(IOException(), all::skipSetModeError) {
            ErrorResult(FileJobErrorAction.NEUTRAL, false)
        }
        assertEquals(ErrorDecision.CANCEL, decision)
    }

    @Test
    fun aCountedStepThatWorksIsCountedAsTransferred() {
        val transferInfo = transferInfoOf(2)
        val posted = mutableListOf<Path>()
        val path = TestPath("/a")
        job.runCountedStep(path, transferInfo, { _, postedPath -> posted.add(postedPath) }, {}) {
            ErrorDecision.SKIP
        }
        assertEquals(1, transferInfo.transferredFileCount)
        assertEquals(2, transferInfo.fileCount)
        assertEquals(listOf<Path>(path), posted)
    }

    @Test
    fun aSkippedCountedStepLeavesOneFileLessToDo() {
        val transferInfo = transferInfoOf(2)
        val posted = mutableListOf<Path>()
        val path = TestPath("/a")
        job.runCountedStep(path, transferInfo, { _, postedPath ->
            posted.add(postedPath)
        }, { throw IOException() }) {
            ErrorDecision.SKIP
        }
        assertEquals(0, transferInfo.transferredFileCount)
        assertEquals(1, transferInfo.fileCount)
        assertEquals(listOf<Path>(path), posted)
    }

    @Test
    fun aCountedStepWithoutTransferInfoPostsNothing() {
        var attempts = 0
        job.runCountedStep(TestPath("/a"), null, { _, _ -> fail("posted") }, { ++attempts }) {
            ErrorDecision.SKIP
        }
        assertEquals(1, attempts)
    }

    private fun transferInfoOf(fileCount: Int): TransferInfo {
        val scanInfo = ScanInfo()
        repeat(fileCount) { scanInfo.incrementFileCount() }
        return TransferInfo(scanInfo, null)
    }

    private fun unexpectedDialog(): ErrorResult {
        fail("asked the user")
        throw AssertionError()
    }

    private class ScriptedDecisions : FileJobDecisions {
        var userActionResult = false
        val userActions = mutableListOf<UserActionRequiredException>()

        override fun error(request: FileJobErrorRequest): ErrorResult =
            throw AssertionError("The tests pass their own dialog")

        override fun conflict(source: FileItem, target: FileItem, type: CopyMoveType) =
            throw AssertionError("No conflicts here")

        override fun userAction(exception: UserActionRequiredException): Boolean {
            userActions += exception
            return userActionResult
        }
    }

    private class TestUserActionRequiredException : UserActionRequiredException("/a") {
        override fun getUserAction(
            continuation: Continuation<Boolean>,
            context: Context
        ): UserAction = throw AssertionError("The decisions are scripted")
    }
}
