/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import java.io.IOException
import java8.nio.file.Path
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.common.UserActionRequiredException
import me.zhanghai.android.files.provider.common.createDirectory
import me.zhanghai.android.files.provider.common.createFile
import me.zhanghai.android.files.util.logWarning
import me.zhanghai.android.files.util.toUserMessage

class CreateFileJob(private val path: Path, private val createDirectory: Boolean) : FileJob() {
    @Throws(IOException::class)
    override fun run() {
        create(path, createDirectory)
    }
}

@Throws(IOException::class)
private fun FileJob.create(path: Path, createDirectory: Boolean) {
    retryUntilDecided({ if (createDirectory) path.createDirectory() else path.createFile() }) { e ->
        e.logWarning("CreateFileJob", "create($path)")
        if (e is UserActionRequiredException && showUserAction(e)) {
            ErrorDecision.RETRY
        } else {
            retryOrCancelDecision(showCreateErrorDialog(path, e))
        }
    }
}

private fun FileJob.showCreateErrorDialog(path: Path, exception: IOException): ErrorResult =
    showErrorDialog(
        getString(R.string.file_job_create_error_title),
        getString(
            R.string.file_job_create_error_message_format,
            getFileName(path),
            exception.toUserMessage(service)
        ),
        getReadOnlyFileStore(path, exception),
        false,
        getString(R.string.retry),
        getString(android.R.string.cancel),
        null
    )
