/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InterruptedIOException
import java8.nio.file.Path
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.mainExecutor
import me.zhanghai.android.files.file.asFileSize
import me.zhanghai.android.files.provider.common.UserActionRequiredException
import me.zhanghai.android.files.provider.common.copyTo
import me.zhanghai.android.files.provider.common.newOutputStream
import me.zhanghai.android.files.util.toUserMessage

class WriteFileJob(
    private val file: Path,
    private val content: ByteArray,
    private val listener: ((Boolean) -> Unit)?
) : FileJob() {
    @Throws(IOException::class)
    override fun run() {
        val successful = write(file, content)
        listener?.let { mainExecutor.execute { it(successful) } }
    }
}

@Throws(IOException::class)
private fun FileJob.write(file: Path, content: ByteArray): Boolean {
    val scanInfo = ScanInfo().apply {
        incrementFileCount()
        addToSize(content.size.toLong())
    }
    var retry: Boolean
    do {
        retry = false
        val transferInfo = TransferInfo(scanInfo, file)
        try {
            file.newOutputStream().use { outputStream ->
                ByteArrayInputStream(content).copyTo(outputStream, PROGRESS_INTERVAL_MILLIS) {
                    transferInfo.addToTransferredSize(it)
                    postWriteNotification(transferInfo)
                }
                postWriteNotification(transferInfo)
            }
        } catch (e: InterruptedIOException) {
            throw e
        } catch (e: IOException) {
            e.printStackTrace()
            if (e is UserActionRequiredException) {
                val result = showUserAction(e)
                if (result) {
                    retry = true
                    continue
                }
            }
            val result = showErrorDialog(
                getString(R.string.file_job_write_error_title, getFileName(file)),
                getString(
                    R.string.file_job_write_error_message_format,
                    getFileName(file),
                    e.toUserMessage(service)
                ),
                getReadOnlyFileStore(file, e),
                false,
                getString(R.string.retry),
                getString(android.R.string.cancel),
                null
            )
            return when (result.action) {
                FileJobErrorAction.POSITIVE -> {
                    retry = true
                    continue
                }

                FileJobErrorAction.NEGATIVE, FileJobErrorAction.CANCELED -> false

                FileJobErrorAction.NEUTRAL -> throw InterruptedIOException()
            }
        }
    } while (retry)
    return true
}

private fun FileJob.postWriteNotification(transferInfo: TransferInfo) {
    if (!transferInfo.shouldPostNotification()) {
        return
    }
    val target = transferInfo.target!!
    val title = getString(R.string.file_job_write_notification_title_format, getFileName(target))
    val size = transferInfo.size
    val sizeString = size.asFileSize().formatHumanReadable(service)
    val transferredSize = transferInfo.transferredSize
    val transferredSizeString = transferredSize.asFileSize().formatHumanReadable(service)
    val text = getString(
        R.string.file_job_transfer_size_notification_text_one_format,
        transferredSizeString,
        sizeString
    )
    val max = size.toInt()
    val progress = transferredSize.toInt()
    postNotification(title, text, null, null, max, progress, false, true)
}
