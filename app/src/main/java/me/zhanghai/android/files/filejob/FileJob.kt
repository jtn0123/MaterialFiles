/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import me.zhanghai.android.files.R
import me.zhanghai.android.files.util.logWarning
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.toUserMessage

/**
 * A unit of file work run by [FileJobService] in its own coroutine. [run] is blocking code: it
 * drives the providers, which are blocking, and waits for the user in dialogs by blocking too,
 * so it executes under [runInterruptible] and cancelling the job's coroutine interrupts its
 * thread, which every provider turns into an [InterruptedIOException].
 */
abstract class FileJob {
    val id = Random().nextInt()

    internal var decisions: FileJobDecisions = AndroidFileJobDecisions(this)

    /** Shows the progress of a scan; the notification needs the service, which tests lack. */
    internal var postScanProgress: (ScanInfo, Int) -> Unit = { scanInfo, titleRes ->
        postScanNotification(scanInfo, titleRes)
    }

    internal lateinit var service: FileJobService
        private set

    /** Files the user (or a "skip all") chose to leave behind after an error. */
    internal var skippedErrorCount = 0
        private set

    /**
     * What a copy, move or extraction reports when it ends, in a notification that outlives the
     * progress one; other jobs have nothing to report beyond a toast on failure.
     */
    internal open val transferResult: TransferResult?
        get() = null

    internal fun recordSkippedError() {
        ++skippedErrorCount
    }

    suspend fun runOn(service: FileJobService) {
        this.service = service
        try {
            runInterruptible(Dispatchers.IO) { run() }
            onSucceeded()
        } catch (e: InterruptedIOException) {
            // Cancellation from the notification or a dialog arrives as a bare
            // InterruptedIOException; a socket timeout is a subclass, but a failure.
            if (e is SocketTimeoutException) {
                onFailed(e)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onFailed(e)
        } finally {
            service.notificationManager.cancel(id)
        }
    }

    private fun onSucceeded() {
        val transferResult = transferResult
        if (transferResult != null) {
            postFinishedNotification(transferResult)
            return
        }
        getSkippedErrorsText()?.let { service.showToast(it) }
    }

    private fun onFailed(e: Exception) {
        e.logWarning("FileJob", "onFailed")
        val message = e.toUserMessage(service)
        val transferResult = transferResult
        if (transferResult != null) {
            postFailedNotification(transferResult, message)
            return
        }
        service.showToast(service.getString(R.string.file_job_failed_format, message))
    }

    internal open fun onFinished() {
        // Most jobs have no result listener; subclasses can release caller-specific state.
    }

    @Throws(IOException::class)
    protected abstract fun run()
}
