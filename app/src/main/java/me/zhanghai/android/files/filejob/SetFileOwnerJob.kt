/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import java.io.IOException
import java8.nio.file.LinkOption
import java8.nio.file.Path
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.common.PosixUser
import me.zhanghai.android.files.provider.common.setOwner
import me.zhanghai.android.files.util.logWarning
import me.zhanghai.android.files.util.toUserMessage

class SetFileOwnerJob(
    private val path: Path,
    private val owner: PosixUser,
    private val recursive: Boolean
) : FileJob() {
    @Throws(IOException::class)
    override fun run() {
        walkSettingAttribute(
            path,
            recursive,
            R.plurals.file_job_set_owner_scan_notification_title_format
        ) { file, attributes, transferInfo, actionAllInfo ->
            setOwner(file, owner, !attributes.isSymbolicLink, transferInfo, actionAllInfo)
        }
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
    val options = if (followLinks) arrayOf() else arrayOf(LinkOption.NOFOLLOW_LINKS)
    runCountedStep(
        path,
        transferInfo,
        FileJob::postSetOwnerNotification,
        { path.setOwner(owner, *options) }
    ) { e ->
        e.logWarning("SetFileOwnerJob", "setOwner($path)")
        decideOnError(e, actionAllInfo::skipSetOwnerError) {
            showRetrySkipCancelDialog(
                getString(R.string.file_job_set_owner_error_title_format, getFileName(path)),
                getString(
                    R.string.file_job_set_owner_error_message_format,
                    getPrincipalName(owner),
                    e.toUserMessage(service)
                ),
                path,
                e
            )
        }
    }
}

private fun FileJob.postSetOwnerNotification(transferInfo: TransferInfo, currentPath: Path) {
    postTransferCountNotification(
        transferInfo,
        currentPath,
        R.string.file_job_set_owner_notification_title_one_format,
        R.plurals.file_job_set_owner_notification_title_multiple_format
    )
}
