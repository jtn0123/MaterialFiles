/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import java.io.IOException
import java.io.InterruptedIOException
import java8.nio.file.Path
import kotlin.reflect.KMutableProperty0
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.common.UserActionRequiredException

/**
 * Runs [attempt] until it succeeds and returns true, or until [decide] chooses for one of its
 * failures to skip it, returning false, or to cancel the job, which is thrown as an
 * [InterruptedIOException]. An [InterruptedIOException] from [attempt] is the job being cancelled
 * already and is passed through.
 */
@Throws(IOException::class)
internal fun retryUntilDecided(
    attempt: () -> Unit,
    decide: (IOException) -> ErrorDecision
): Boolean {
    var decision: ErrorDecision
    do {
        decision = try {
            attempt()
            return true
        } catch (e: InterruptedIOException) {
            throw e
        } catch (e: IOException) {
            decide(e)
        }
    } while (decision == ErrorDecision.RETRY)
    if (decision == ErrorDecision.CANCEL) {
        throw InterruptedIOException()
    }
    return false
}

/**
 * Decides what to do about [exception] from a step the user may skip: skip it right away after an
 * earlier "skip all" in [skipAll], retry once the user has done what the step needs, and otherwise
 * ask with [showDialog], whose buttons are retry, skip and cancel. A file skipped for an error is
 * counted for the job's final toast; one whose dialog was dismissed is not.
 */
internal fun FileJob.decideOnError(
    exception: IOException,
    skipAll: KMutableProperty0<Boolean>,
    showDialog: () -> ErrorResult
): ErrorDecision {
    if (skipAll.get()) {
        recordSkippedError()
        return ErrorDecision.SKIP
    }
    if (exception is UserActionRequiredException && showUserAction(exception)) {
        return ErrorDecision.RETRY
    }
    val result = showDialog()
    if (result.action == FileJobErrorAction.NEGATIVE) {
        recordSkippedError()
    }
    return errorDecision(result, skipAll)
}

/**
 * Runs one counted step of a job, such as deleting a file or setting its mode, on [path]: [attempt]
 * is retried, skipped or cancelled as [decide] says, and the file is counted as done or skipped in
 * [transferInfo], if the job has one.
 */
@Throws(IOException::class)
internal fun FileJob.runCountedStep(
    path: Path,
    transferInfo: TransferInfo?,
    postNotification: FileJob.(TransferInfo, Path) -> Unit,
    attempt: () -> Unit,
    decide: (IOException) -> ErrorDecision
) {
    val isDone = retryUntilDecided(attempt, decide)
    if (transferInfo == null) {
        return
    }
    if (isDone) {
        transferInfo.incrementTransferredFileCount()
    } else {
        transferInfo.skipFileIgnoringSize()
    }
    postNotification(transferInfo, path)
}

/** Shows the error dialog for [exception] on [path], with retry, skip and cancel buttons. */
internal fun FileJob.showRetrySkipCancelDialog(
    title: CharSequence,
    message: CharSequence,
    path: Path,
    exception: IOException
): ErrorResult = showErrorDialog(
    title,
    message,
    getReadOnlyFileStore(path, exception),
    true,
    getString(R.string.retry),
    getString(R.string.skip),
    getString(android.R.string.cancel)
)
