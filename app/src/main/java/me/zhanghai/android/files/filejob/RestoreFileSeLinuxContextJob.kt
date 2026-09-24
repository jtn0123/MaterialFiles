/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import java.io.IOException
import java8.nio.file.LinkOption
import java8.nio.file.Path
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.common.restoreSeLinuxContext
import me.zhanghai.android.files.util.logWarning
import me.zhanghai.android.files.util.toUserMessage

class RestoreFileSeLinuxContextJob(private val path: Path, private val recursive: Boolean) :
    FileJob() {
    @Throws(IOException::class)
    override fun run() {
        walkSettingAttribute(
            path,
            recursive,
            R.plurals.file_job_restore_selinux_context_scan_notification_title_format
        ) { file, attributes, transferInfo, actionAllInfo ->
            restoreSeLinuxContext(file, !attributes.isSymbolicLink, transferInfo, actionAllInfo)
        }
    }
}

@Throws(IOException::class)
private fun FileJob.restoreSeLinuxContext(
    path: Path,
    followLinks: Boolean,
    transferInfo: TransferInfo,
    actionAllInfo: ActionAllInfo
) {
    val options = if (followLinks) arrayOf() else arrayOf(LinkOption.NOFOLLOW_LINKS)
    runCountedStep(
        path,
        transferInfo,
        FileJob::postRestoreSeLinuxContextNotification,
        { path.restoreSeLinuxContext(*options) }
    ) { e ->
        e.logWarning("RestoreFileSeLinuxContextJob", "restoreSeLinuxContext($path)")
        decideOnError(e, actionAllInfo::skipRestoreSeLinuxContextError) {
            showRetrySkipCancelDialog(
                getString(R.string.file_job_restore_selinux_context_error_title),
                getString(
                    R.string.file_job_restore_selinux_context_error_message_format,
                    getFileName(path),
                    e.toUserMessage(service)
                ),
                path,
                e
            )
        }
    }
}

private fun FileJob.postRestoreSeLinuxContextNotification(
    transferInfo: TransferInfo,
    currentPath: Path
) {
    postTransferCountNotification(
        transferInfo,
        currentPath,
        R.string.file_job_restore_selinux_context_notification_title_one_format,
        R.plurals.file_job_restore_selinux_context_notification_title_multiple_format
    )
}
