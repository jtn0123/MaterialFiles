/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import java.io.IOException
import java8.nio.file.LinkOption
import java8.nio.file.Path
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.common.PosixFileModeBit
import me.zhanghai.android.files.provider.common.getMode
import me.zhanghai.android.files.provider.common.setMode
import me.zhanghai.android.files.provider.common.toModeString
import me.zhanghai.android.files.util.logWarning
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
        walkSettingAttribute(
            path,
            recursive,
            R.plurals.file_job_set_mode_scan_notification_title_format
        ) { file, attributes, transferInfo, actionAllInfo ->
            if (attributes.isSymbolicLink) {
                // We cannot set mode on symbolic links.
                transferInfo.skipFileIgnoringSize()
            } else {
                // The file may actually be a directory if we are not entering it.
                val mode = if (!attributes.isDirectory) getFileMode(file) else mode
                setMode(file, mode, transferInfo, actionAllInfo)
            }
        }
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
    runCountedStep(
        path,
        transferInfo,
        FileJob::postSetModeNotification,
        // This will always follow symbolic links.
        { path.setMode(mode) }
    ) { e ->
        e.logWarning("SetFileModeJob", "setMode($path)")
        decideOnError(e, actionAllInfo::skipSetModeError) {
            showRetrySkipCancelDialog(
                getString(R.string.file_job_set_mode_error_title_format, getFileName(path)),
                getString(
                    R.string.file_job_set_mode_error_message_format,
                    mode.toModeString(),
                    e.toUserMessage(service)
                ),
                path,
                e
            )
        }
    }
}

private fun FileJob.postSetModeNotification(transferInfo: TransferInfo, currentPath: Path) {
    postTransferCountNotification(
        transferInfo,
        currentPath,
        R.string.file_job_set_mode_notification_title_one_format,
        R.plurals.file_job_set_mode_notification_title_multiple_format
    )
}
