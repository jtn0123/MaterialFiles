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
import me.zhanghai.android.files.provider.common.PosixFileModeBit
import me.zhanghai.android.files.provider.common.UserActionRequiredException
import me.zhanghai.android.files.provider.common.getMode
import me.zhanghai.android.files.provider.common.setMode
import me.zhanghai.android.files.provider.common.toModeString
import me.zhanghai.android.files.util.toEnumSet
import me.zhanghai.android.files.util.toUserMessage

class SetFileModeJob(
    private val path: Path,
    private val mode: Set<PosixFileModeBit>,
    private val recursive: Boolean,
    private val uppercaseX: Boolean
) : FileJob() {
    @Throws(IOException::class)
    override fun run() {
        val scanInfo = scan(
            path,
            recursive,
            R.plurals.file_job_set_mode_scan_notification_title_format
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
                    if (attributes.isSymbolicLink) {
                        // We cannot set mode on symbolic links.
                        transferInfo.skipFileIgnoringSize()
                        return FileVisitResult.CONTINUE
                    }
                    // The file may actually be a directory if we are not entering it.
                    val mode = if (!attributes.isDirectory) getFileMode(file) else mode
                    setMode(file, mode, transferInfo, actionAllInfo)
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

    @Throws(IOException::class)
    private fun getFileMode(file: Path): Set<PosixFileModeBit> {
        if (file == path || !uppercaseX) {
            return mode
        }
        val mode = mode.toEnumSet()
        val currentMode = file.getMode(LinkOption.NOFOLLOW_LINKS)!!
        if (PosixFileModeBit.OWNER_EXECUTE !in currentMode) {
            mode -= PosixFileModeBit.OWNER_EXECUTE
        }
        if (PosixFileModeBit.GROUP_EXECUTE !in currentMode) {
            mode -= PosixFileModeBit.GROUP_EXECUTE
        }
        if (PosixFileModeBit.OTHERS_EXECUTE !in currentMode) {
            mode -= PosixFileModeBit.OTHERS_EXECUTE
        }
        return mode
    }
}

@Throws(IOException::class)
private fun FileJob.setMode(
    path: Path,
    mode: Set<PosixFileModeBit>,
    transferInfo: TransferInfo,
    actionAllInfo: ActionAllInfo
) {
    var retry: Boolean
    do {
        retry = false
        try {
            // This will always follow symbolic links.
            path.setMode(mode)
            transferInfo.incrementTransferredFileCount()
            postSetModeNotification(transferInfo, path)
        } catch (e: InterruptedIOException) {
            throw e
        } catch (e: IOException) {
            e.printStackTrace()
            if (actionAllInfo.skipSetModeError) {
                recordSkippedError()
                transferInfo.skipFileIgnoringSize()
                postSetModeNotification(transferInfo, path)
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
                getString(R.string.file_job_set_mode_error_title_format, getFileName(path)),
                getString(
                    R.string.file_job_set_mode_error_message_format,
                    mode.toModeString(),
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
                        actionAllInfo.skipSetModeError = true
                    }
                    transferInfo.skipFileIgnoringSize()
                    postSetModeNotification(transferInfo, path)
                    return
                }

                FileJobErrorAction.CANCELED -> {
                    transferInfo.skipFileIgnoringSize()
                    postSetModeNotification(transferInfo, path)
                    return
                }

                FileJobErrorAction.NEUTRAL -> throw InterruptedIOException()

                else -> throw AssertionError(result.action)
            }
        }
    } while (retry)
}

private fun FileJob.postSetModeNotification(transferInfo: TransferInfo, currentPath: Path) {
    postTransferCountNotification(
        transferInfo,
        currentPath,
        R.string.file_job_set_mode_notification_title_one_format,
        R.plurals.file_job_set_mode_notification_title_multiple_format
    )
}
