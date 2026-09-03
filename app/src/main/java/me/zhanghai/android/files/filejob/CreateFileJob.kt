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
import me.zhanghai.android.files.provider.common.createDirectory
import me.zhanghai.android.files.provider.common.createFile
import me.zhanghai.android.files.util.toUserMessage

class CreateFileJob(private val path: Path, private val createDirectory: Boolean) : FileJob() {
    @Throws(IOException::class)
    override fun run() {
        create(path, createDirectory)
    }
}

@Throws(IOException::class)
private fun FileJob.create(path: Path, createDirectory: Boolean) {
    var retry: Boolean
    do {
        retry = false
        try {
            if (createDirectory) {
                path.createDirectory()
            } else {
                path.createFile()
            }
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
                getString(R.string.file_job_create_error_title),
                getString(
                    R.string.file_job_create_error_message_format,
                    getFileName(path),
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
