package me.zhanghai.android.files.provider.ftp

import java.io.IOException
import java8.nio.file.FileSystemException
import me.zhanghai.android.files.provider.ftp.client.NegativeReplyCodeException

fun IOException.toFileSystemExceptionForFtp(
    file: String?,
    other: String? = null
): FileSystemException = when (this) {
    is NegativeReplyCodeException -> toFileSystemException(file, other)

    else ->
        FileSystemException(file, other, message)
            .apply { initCause(this@toFileSystemExceptionForFtp) }
}
