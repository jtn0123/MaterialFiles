/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import java.io.IOException
import java8.nio.file.LinkOption
import java8.nio.file.Path
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.common.setSeLinuxContext
import me.zhanghai.android.files.provider.common.toByteString
import me.zhanghai.android.files.util.logWarning
import me.zhanghai.android.files.util.toUserMessage

class SetFileSeLinuxContextJob(
    private val path: Path,
    private val seLinuxContext: String,
    private val recursive: Boolean
) : FileJob() {
    @Throws(IOException::class)
    override fun run() {
        walkSettingAttribute(
            path,
            recursive,
            R.plurals.file_job_set_selinux_context_scan_notification_title_format
        ) { file, attributes, transferInfo, actionAllInfo ->
            setSeLinuxContext(
                file,
                seLinuxContext,
                !attributes.isSymbolicLink,
                transferInfo,
                actionAllInfo
            )
        }
    }
}

@Throws(IOException::class)
private fun FileJob.setSeLinuxContext(
    path: Path,
    seLinuxContext: String,
    followLinks: Boolean,
    transferInfo: TransferInfo,
    actionAllInfo: ActionAllInfo
) {
    val options = if (followLinks) arrayOf() else arrayOf(LinkOption.NOFOLLOW_LINKS)
    runCountedStep(
        path,
        transferInfo,
        FileJob::postSetSeLinuxContextNotification,
        { path.setSeLinuxContext(seLinuxContext.toByteString(), *options) }
    ) { e ->
        e.logWarning("SetFileSeLinuxContextJob", "setSeLinuxContext($path)")
        decideOnError(e, actionAllInfo::skipSetSeLinuxContextError) {
            showRetrySkipCancelDialog(
                getString(
                    R.string.file_job_set_selinux_context_error_title_format,
                    getFileName(path)
                ),
                getString(
                    R.string.file_job_set_selinux_context_error_message_format,
                    seLinuxContext,
                    e.toUserMessage(service)
                ),
                path,
                e
            )
        }
    }
}

private fun FileJob.postSetSeLinuxContextNotification(
    transferInfo: TransferInfo,
    currentPath: Path
) {
    postTransferCountNotification(
        transferInfo,
        currentPath,
        R.string.file_job_set_selinux_context_notification_title_one_format,
        R.plurals.file_job_set_selinux_context_notification_title_multiple_format
    )
}
