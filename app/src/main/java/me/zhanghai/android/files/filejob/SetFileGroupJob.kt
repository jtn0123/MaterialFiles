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
import me.zhanghai.android.files.provider.common.PosixGroup
import me.zhanghai.android.files.provider.common.UserActionRequiredException
import me.zhanghai.android.files.provider.common.setGroup
import me.zhanghai.android.files.util.toUserMessage

class SetFileGroupJob(
    private val path: Path,
    private val group: PosixGroup,
    private val recursive: Boolean
) : FileJob() {
    @Throws(IOException::class)
    override fun run() {
        val scanInfo = scan(
            path,
            recursive,
            R.plurals.file_job_set_group_scan_notification_title_format
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
                    setGroup(file, group, !attributes.isSymbolicLink, transferInfo, actionAllInfo)
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
private fun FileJob.setGroup(
    path: Path,
    group: PosixGroup,
    followLinks: Boolean,
    transferInfo: TransferInfo,
    actionAllInfo: ActionAllInfo
) {
    var retry: Boolean
    do {
        retry = false
        try {
            val options = if (followLinks) arrayOf() else arrayOf(LinkOption.NOFOLLOW_LINKS)
            path.setGroup(group, *options)
            transferInfo.incrementTransferredFileCount()
            postSetGroupNotification(transferInfo, path)
        } catch (e: InterruptedIOException) {
            throw e
        } catch (e: IOException) {
            e.printStackTrace()
            if (actionAllInfo.skipSetGroupError) {
                transferInfo.skipFileIgnoringSize()
                postSetGroupNotification(transferInfo, path)
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
                getString(R.string.file_job_set_group_error_title_format, getFileName(path)),
                getString(
                    R.string.file_job_set_group_error_message_format,
                    getPrincipalName(group),
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
                        actionAllInfo.skipSetGroupError = true
                    }
                    transferInfo.skipFileIgnoringSize()
                    postSetGroupNotification(transferInfo, path)
                    return
                }

                FileJobErrorAction.CANCELED -> {
                    transferInfo.skipFileIgnoringSize()
                    postSetGroupNotification(transferInfo, path)
                    return
                }

                FileJobErrorAction.NEUTRAL -> throw InterruptedIOException()

                else -> throw AssertionError(result.action)
            }
        }
    } while (retry)
}

private fun FileJob.postSetGroupNotification(transferInfo: TransferInfo, currentPath: Path) {
    postTransferCountNotification(
        transferInfo,
        currentPath,
        R.string.file_job_set_group_notification_title_one_format,
        R.plurals.file_job_set_group_notification_title_multiple_format
    )
}
