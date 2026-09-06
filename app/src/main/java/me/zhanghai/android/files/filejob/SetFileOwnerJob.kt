/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import java.io.IOException
import java.io.InterruptedIOException
import java8.nio.file.FileVisitResult
import java8.nio.file.LinkOption
import java8.nio.file.Path
import java8.nio.file.SimpleFileVisitor
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.common.PosixUser
import me.zhanghai.android.files.provider.common.UserActionRequiredException
import me.zhanghai.android.files.provider.common.setOwner
import me.zhanghai.android.files.util.toUserMessage

class SetFileOwnerJob(
    private val path: Path,
    private val owner: PosixUser,
    private val recursive: Boolean
) : FileJob() {
    @Throws(IOException::class)
    override fun run() {
        val scanInfo = scan(
            path,
            recursive,
            R.plurals.file_job_set_owner_scan_notification_title_format
        )
        val transferInfo = TransferInfo(scanInfo, null)
        val actionAllInfo = ActionAllInfo()
        walkFileTreeForSettingAttributes(
            path,
            recursive,
            object : SimpleFileVisitor<Path>() {
                @Throws(IOException::class)
                override fun preVisitDirectory(
                    directory: Path,
                    attributes: BasicFileAttributes
                ): FileVisitResult = visitFile(directory, attributes)

                @Throws(IOException::class)
                override fun visitFile(
                    file: Path,
                    attributes: BasicFileAttributes
                ): FileVisitResult {
                    setOwner(file, owner, !attributes.isSymbolicLink, transferInfo, actionAllInfo)
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
                    return super.postVisitDirectory(directory, exception)
                }
            }
        )
    }
}

@Throws(IOException::class)
private fun FileJob.setOwner(
    path: Path,
    owner: PosixUser,
    followLinks: Boolean,
    transferInfo: TransferInfo,
    actionAllInfo: ActionAllInfo
) {
    var retry: Boolean
    do {
        retry = false
        try {
            val options = if (followLinks) arrayOf() else arrayOf(LinkOption.NOFOLLOW_LINKS)
            path.setOwner(owner, *options)
            transferInfo.incrementTransferredFileCount()
            postSetOwnerNotification(transferInfo, path)
        } catch (e: InterruptedIOException) {
            throw e
        } catch (e: IOException) {
            e.printStackTrace()
            if (actionAllInfo.skipSetOwnerError) {
                recordSkippedError()
                transferInfo.skipFileIgnoringSize()
                postSetOwnerNotification(transferInfo, path)
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
                getString(R.string.file_job_set_owner_error_title_format, getFileName(path)),
                getString(
                    R.string.file_job_set_owner_error_message_format,
                    getPrincipalName(owner),
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
                    recordSkippedError()
                    if (result.isAll) {
                        actionAllInfo.skipSetOwnerError = true
                    }
                    transferInfo.skipFileIgnoringSize()
                    postSetOwnerNotification(transferInfo, path)
                    return
                }

                FileJobErrorAction.CANCELED -> {
                    transferInfo.skipFileIgnoringSize()
                    postSetOwnerNotification(transferInfo, path)
                    return
                }

                FileJobErrorAction.NEUTRAL -> throw InterruptedIOException()

                else -> throw AssertionError(result.action)
            }
        }
    } while (retry)
}

private fun FileJob.postSetOwnerNotification(transferInfo: TransferInfo, currentPath: Path) {
    postTransferCountNotification(
        transferInfo,
        currentPath,
        R.string.file_job_set_owner_notification_title_one_format,
        R.plurals.file_job_set_owner_notification_title_multiple_format
    )
}
