/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import java.io.IOException
import java8.nio.file.LinkOption
import java8.nio.file.Path
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.common.PosixGroup
import me.zhanghai.android.files.provider.common.setGroup
import me.zhanghai.android.files.util.logWarning
import me.zhanghai.android.files.util.toUserMessage

class SetFileGroupJob(
    private val path: Path,
    private val group: PosixGroup,
    private val recursive: Boolean
) : FileJob() {
    @Throws(IOException::class)
    override fun run() {
        walkSettingAttribute(
            path,
            recursive,
            R.plurals.file_job_set_group_scan_notification_title_format
        ) { file, attributes, transferInfo, actionAllInfo ->
            setGroup(file, group, !attributes.isSymbolicLink, transferInfo, actionAllInfo)
        }
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
    val options = if (followLinks) arrayOf() else arrayOf(LinkOption.NOFOLLOW_LINKS)
    runCountedStep(
        path,
        transferInfo,
        FileJob::postSetGroupNotification,
        { path.setGroup(group, *options) }
    ) { e ->
        e.logWarning("SetFileGroupJob", "setGroup($path)")
        decideOnError(e, actionAllInfo::skipSetGroupError) {
            showRetrySkipCancelDialog(
                getString(R.string.file_job_set_group_error_title_format, getFileName(path)),
                getString(
                    R.string.file_job_set_group_error_message_format,
                    getPrincipalName(group),
                    e.toUserMessage(service)
                ),
                path,
                e
            )
        }
    }
}

private fun FileJob.postSetGroupNotification(transferInfo: TransferInfo, currentPath: Path) {
    postTransferCountNotification(
        transferInfo,
        currentPath,
        R.string.file_job_set_group_notification_title_one_format,
        R.plurals.file_job_set_group_notification_title_multiple_format
    )
}
