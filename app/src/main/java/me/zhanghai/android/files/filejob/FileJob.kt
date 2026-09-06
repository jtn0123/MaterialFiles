/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.Random
import me.zhanghai.android.files.R
import me.zhanghai.android.files.util.getQuantityString
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.toUserMessage

abstract class FileJob {
    val id = Random().nextInt()

    internal lateinit var service: FileJobService
        private set

    /** Files the user (or a "skip all") chose to leave behind after an error. */
    private var skippedErrorCount = 0

    internal fun recordSkippedError() {
        ++skippedErrorCount
    }

    fun runOn(service: FileJobService) {
        this.service = service
        try {
            run()
            if (skippedErrorCount > 0) {
                service.showToast(
                    service.getQuantityString(
                        R.plurals.file_job_finished_with_skipped_errors_format,
                        skippedErrorCount,
                        skippedErrorCount
                    )
                )
            }
        } catch (e: InterruptedIOException) {
            // Cancellation from the notification or a dialog arrives as a bare
            // InterruptedIOException; a socket timeout is a subclass, but a failure.
            if (e is SocketTimeoutException) {
                onFailed(e)
            }
        } catch (e: Exception) {
            onFailed(e)
        } finally {
            service.notificationManager.cancel(id)
        }
    }

    private fun onFailed(e: Exception) {
        e.printStackTrace()
        service.showToast(
            service.getString(R.string.file_job_failed_format, e.toUserMessage(service))
        )
    }

    @Throws(IOException::class)
    protected abstract fun run()
}
