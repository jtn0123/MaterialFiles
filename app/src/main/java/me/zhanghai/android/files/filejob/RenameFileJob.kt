/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import java.io.IOException
import java.io.InterruptedIOException
import java8.nio.file.Path
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.common.UserActionRequiredException
import me.zhanghai.android.files.util.toUserMessage

class RenameFileJob(private val path: Path, private val newName: String) : FileJob() {
    @Throws(IOException::class)
    override fun run() {
        val newPath = path.resolveSibling(newName)
        rename(path, newPath)
    }
}

@Throws(IOException::class)
private fun FileJob.rename(path: Path, newPath: Path) {
    var retry: Boolean
    do {
        retry = false
        try {
            moveAtomically(path, newPath)
        } catch (e: InterruptedIOException) {
            throw e
        } catch (e: IOException) {
            e.printStackTrace()
            if (e is UserActionRequiredException) {
                val result = showUserAction(e)
                if (result) {
                    retry = true
                    continue
                }
            }
            val result = showErrorDialog(
                getString(R.string.file_job_rename_error_title_format, getFileName(path)),
                getString(
                    R.string.file_job_rename_error_message_format,
                    getFileName(newPath),
                    e.toUserMessage(service)
                ),
                getReadOnlyFileStore(path, e),
                false,
                getString(R.string.retry),
                getString(android.R.string.cancel),
                null
            )
            when (result.action) {
                FileJobErrorAction.POSITIVE -> {
                    retry = true
                    continue
                }

                FileJobErrorAction.NEGATIVE, FileJobErrorAction.CANCELED ->
                    throw InterruptedIOException()

                else -> throw AssertionError(result.action)
            }
        }
    } while (retry)
}
