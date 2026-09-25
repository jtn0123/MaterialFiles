/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import java.io.IOException
import java8.nio.file.FileVisitResult
import java8.nio.file.Path
import java8.nio.file.SimpleFileVisitor
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.common.delete
import me.zhanghai.android.files.util.logWarning
import me.zhanghai.android.files.util.toUserMessage

class DeleteFileJob(private val paths: List<Path>) : FileJob() {
    @Throws(IOException::class)
    override fun run() {
        val actionAllInfo = ActionAllInfo()
        val scanInfo = scan(
            paths,
            R.plurals.file_job_delete_scan_notification_title_format,
            actionAllInfo
        )
        val transferInfo = TransferInfo(scanInfo, null)
        for (path in paths) {
            walkFileTreeAskingOnErrors(
                path,
                DeletingVisitor {
                    delete(it, transferInfo, actionAllInfo)
                    throwIfInterrupted()
                },
                actionAllInfo,
                transferInfo
            )
            throwIfInterrupted()
        }
    }
}

/**
 * Deletes each file, and each directory after its children. Meant to be wrapped in a
 * [WalkErrorVisitor], which leaves out a directory that could not be emptied completely.
 */
internal class DeletingVisitor(private val delete: (Path) -> Unit) : SimpleFileVisitor<Path>() {
    @Throws(IOException::class)
    override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
        delete(file)
        return FileVisitResult.CONTINUE
    }

    @Throws(IOException::class)
    override fun postVisitDirectory(directory: Path, exception: IOException?): FileVisitResult {
        if (exception != null) {
            throw exception
        }
        delete(directory)
        return FileVisitResult.CONTINUE
    }
}

@Throws(IOException::class)
internal fun FileJob.delete(path: Path, transferInfo: TransferInfo?, actionAllInfo: ActionAllInfo) {
    runCountedStep(path, transferInfo, FileJob::postDeleteNotification, { path.delete() }) { e ->
        e.logWarning("DeleteFileJob", "delete($path)")
        decideOnError(e, actionAllInfo::skipDeleteError) {
            showRetrySkipCancelDialog(
                getString(R.string.file_job_delete_error_title),
                getString(
                    R.string.file_job_delete_error_message_format,
                    getFileName(path),
                    e.toUserMessage(service)
                ),
                path,
                e
            )
        }
    }
}

private fun FileJob.postDeleteNotification(transferInfo: TransferInfo, currentPath: Path) {
    postTransferCountNotification(
        transferInfo,
        currentPath,
        R.string.file_job_delete_notification_title_one_format,
        R.plurals.file_job_delete_notification_title_multiple_format
    )
}
