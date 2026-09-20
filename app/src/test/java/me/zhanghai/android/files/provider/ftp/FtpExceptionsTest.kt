/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.ftp

import java.io.IOException
import java8.nio.file.AccessDeniedException
import java8.nio.file.FileSystemException
import java8.nio.file.NoSuchFileException
import me.zhanghai.android.files.provider.common.InvalidFileNameException
import me.zhanghai.android.files.provider.ftp.client.NegativeReplyCodeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the file list shows for a failure is decided here: the reply code a server sent has to
 * become the matching file system exception.
 */
class FtpExceptionsTest {
    @Test
    fun aReplyCodeBecomesTheMatchingException() {
        assertTrue(exceptionFor(550) is NoSuchFileException)
        assertTrue(exceptionFor(530) is AccessDeniedException)
        assertTrue(exceptionFor(532) is AccessDeniedException)
        assertTrue(exceptionFor(553) is InvalidFileNameException)
    }

    @Test
    fun anUnknownReplyCodeStillNamesTheFileAndTheReply() {
        val exception = exceptionFor(426, "426 Connection closed; transfer aborted.")
        assertEquals(FileSystemException::class.java, exception.javaClass)
        assertEquals("/directory/file.txt", exception.file)
        assertEquals("/directory/other.txt", exception.otherFile)
        assertEquals("426 Connection closed; transfer aborted.", exception.reason)
    }

    @Test
    fun theReplyIsKeptAsTheCauseSoThatItCanBeLogged() {
        val replyException = NegativeReplyCodeException(550, "550 No such file.")
        val exception = replyException.toFileSystemExceptionForFtp("/file.txt")
        assertSame(replyException, exception.cause)
        assertEquals("/file.txt", exception.file)
    }

    @Test
    fun anExceptionThatIsNotAReplyIsWrappedAsIs() {
        val cause = IOException("Connection reset")
        val exception = cause.toFileSystemExceptionForFtp("/file.txt")
        assertEquals(FileSystemException::class.java, exception.javaClass)
        assertSame(cause, exception.cause)
        assertEquals("Connection reset", exception.reason)
    }

    private fun exceptionFor(
        replyCode: Int,
        replyString: String = "$replyCode Reply."
    ): FileSystemException = NegativeReplyCodeException(replyCode, replyString)
        .toFileSystemExceptionForFtp("/directory/file.txt", "/directory/other.txt")
}
