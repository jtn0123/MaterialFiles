/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import java.io.IOException
import java.io.InterruptedIOException
import java8.nio.file.FileVisitResult
import java8.nio.file.Files
import java8.nio.file.Path
import java8.nio.file.SimpleFileVisitor
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.common.UserActionRequiredException
import me.zhanghai.android.files.provider.common.delete
import me.zhanghai.android.files.util.toUserMessage

class DeleteFileJob(private val paths: List<Path>) : FileJob() {
    @Throws(IOException::class)
    override fun run() {
        val scanInfo = scan(paths, R.plurals.file_job_delete_scan_notification_title_format)
        val transferInfo = TransferInfo(scanInfo, null)
        val actionAllInfo = ActionAllInfo()
        for (path in paths) {
            deleteRecursively(path, transferInfo, actionAllInfo)
            throwIfInterrupted()
        }
    }

    @Throws(IOException::class)
    private fun deleteRecursively(
        path: Path,
        transferInfo: TransferInfo,
        actionAllInfo: ActionAllInfo
    ) {
        Files.walkFileTree(
            path,
            object : SimpleFileVisitor<Path>() {
                @Throws(IOException::class)
                override fun visitFile(
                    file: Path,
                    attributes: BasicFileAttributes
                ): FileVisitResult {
                    delete(file, transferInfo, actionAllInfo)
                    throwIfInterrupted()
                    return FileVisitResult.CONTINUE
                }

                @Throws(IOException::class)
                override fun visitFileFailed(file: Path, exception: IOException): FileVisitResult {
                    // TODO: Prompt retry, skip, skip-all or abort.
                    return super.visitFileFailed(file, exception)
                }

                @Throws(IOException::class)
                override fun postVisitDirectory(
                    directory: Path,
                    exception: IOException?
                ): FileVisitResult {
                    // TODO: Prompt retry, skip, skip-all or abort.
                    if (exception != null) {
                        throw exception
                    }
                    delete(directory, transferInfo, actionAllInfo)
                    throwIfInterrupted()
                    return FileVisitResult.CONTINUE
                }
            }
        )
    }
}

@Throws(IOException::class)
internal fun FileJob.delete(path: Path, transferInfo: TransferInfo?, actionAllInfo: ActionAllInfo) {
    var retry: Boolean
    do {
        retry = false
        try {
            path.delete()
            if (transferInfo != null) {
                transferInfo.incrementTransferredFileCount()
                postDeleteNotification(transferInfo, path)
            }
        } catch (e: InterruptedIOException) {
            throw e
        } catch (e: IOException) {
            e.printStackTrace()
            if (actionAllInfo.skipDeleteError) {
                if (transferInfo != null) {
                    transferInfo.skipFileIgnoringSize()
                    postDeleteNotification(transferInfo, path)
                }
                return
            }
            if (e is UserActionRequiredException) {
                val result = showUserAction(e)
                if (result) {
                    retry = true
                    continue
                }
            }
            val result = showErrorDialog(
                getString(R.string.file_job_delete_error_title),
                getString(
                    R.string.file_job_delete_error_message_format,
                    getFileName(path),
                    e.toUserMessage(service)
                ),
                getReadOnlyFileStore(path, e),
                true,
                getString(R.string.retry),
                getString(R.string.skip),
                getString(android.R.string.cancel)
            )
            when (result.action) {
                FileJobErrorAction.POSITIVE -> {
                    retry = true
                    continue
                }

                FileJobErrorAction.NEGATIVE -> {
                    if (result.isAll) {
                        actionAllInfo.skipDeleteError = true
                    }
                    if (transferInfo != null) {
                        transferInfo.skipFileIgnoringSize()
                        postDeleteNotification(transferInfo, path)
                    }
                    return
                }

                FileJobErrorAction.CANCELED -> {
                    if (transferInfo != null) {
                        transferInfo.skipFileIgnoringSize()
                        postDeleteNotification(transferInfo, path)
                    }
                    return
                }

                FileJobErrorAction.NEUTRAL -> throw InterruptedIOException()

                else -> throw AssertionError(result.action)
            }
        }
    } while (retry)
}

private fun FileJob.postDeleteNotification(transferInfo: TransferInfo, currentPath: Path) {
    postTransferCountNotification(
        transferInfo,
        currentPath,
        R.string.file_job_delete_notification_title_one_format,
        R.plurals.file_job_delete_notification_title_multiple_format
    )
}
