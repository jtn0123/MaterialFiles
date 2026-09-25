package me.zhanghai.android.files.provider.ftp.client

import java.io.IOException
import java8.nio.file.AccessDeniedException
import java8.nio.file.FileSystemException
import java8.nio.file.NoSuchFileException
import me.zhanghai.android.files.provider.common.AuthenticationFailedException
import me.zhanghai.android.files.provider.common.InvalidFileNameException
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply

class NegativeReplyCodeException(private val replyCode: Int, replyString: String) :
    IOException(replyString) {
    fun toFileSystemException(file: String?, other: String? = null): FileSystemException =
        when (replyCode) {
            // 530 is the answer to a rejected USER/PASS; 532 asks for an account on top of a
            // login that did work, which is about what may be stored and not about who we are.
            FTPReply.NOT_LOGGED_IN -> AuthenticationFailedException(file, other, message)

            FTPReply.NEED_ACCOUNT_FOR_STORING_FILES -> AccessDeniedException(file, other, message)

            FTPReply.FILE_UNAVAILABLE -> NoSuchFileException(file, other, message)

            FTPReply.FILE_NAME_NOT_ALLOWED -> InvalidFileNameException(file, other, message)

            else -> FileSystemException(file, other, message)
        }.apply { initCause(this@NegativeReplyCodeException) }
}

internal fun FTPClient.createNegativeReplyCodeException() =
    NegativeReplyCodeException(replyCode, replyString)

/**
 * The exception for a file the server returned no entry for.
 *
 * Servers disagree on how they say that a file isn't there: RFC 3659 asks for 550, Apache
 * FtpServer (which Material Files itself runs) answers MLST with 501, and a server without MLST
 * lists the parent directory successfully and simply doesn't mention the file. All of them mean
 * the same thing, so all of them become a [java8.nio.file.NoSuchFileException]; only being turned
 * away at the login is kept as it is, so that it isn't reported as a missing file.
 */
internal fun FTPClient.createNoSuchFileException(): NegativeReplyCodeException = when (replyCode) {
    FTPReply.NOT_LOGGED_IN, FTPReply.NEED_ACCOUNT_FOR_STORING_FILES ->
        createNegativeReplyCodeException()

    else ->
        NegativeReplyCodeException(
            FTPReply.FILE_UNAVAILABLE,
            if (FTPReply.isPositiveCompletion(replyCode)) {
                "No such file"
            } else {
                replyString
            }
        )
}

internal fun FTPClient.throwNegativeReplyCodeException(): Nothing =
    throw createNegativeReplyCodeException()
