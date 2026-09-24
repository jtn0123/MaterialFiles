/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive

import android.system.OsConstants
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java8.nio.file.FileSystemException
import me.zhanghai.android.files.provider.common.TestPath
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How what libarchive reports becomes what the user sees: an interruption stays an interruption,
 * a missing or wrong password asks for one, and anything else is an error for the archive file.
 */
class ArchiveExceptionExtensionsTest {
    private val file = TestPath("/archive.zip")

    // libarchive's ARCHIVE_ERRNO_MISC, which its zip reader reports password problems with.
    private val errnoMisc = -1

    @Test
    fun anInterruptionStaysAnInterruption() {
        val exception = ArchiveException(OsConstants.EINTR, "InputStream.read")

        val mapped = exception.toFileSystemOrInterruptedIOException(file)

        assertSame(InterruptedIOException::class.java, mapped.javaClass)
        assertEquals("InputStream.read", mapped.message)
        assertSame(exception, mapped.cause)
    }

    @Test
    fun aMissingOrWrongPasswordAsksForOne() {
        for (message in listOf("Incorrect passphrase", "Passphrase required for this entry")) {
            val exception = ArchiveException(errnoMisc, message)

            val mapped = exception.toFileSystemOrInterruptedIOException(file)

            assertTrue(message, mapped is ArchivePasswordRequiredException)
            mapped as ArchivePasswordRequiredException
            assertEquals("/archive.zip", mapped.file)
            assertEquals(message, mapped.reason)
            assertSame(exception, mapped.cause)
        }
    }

    @Test
    fun onlyThoseExactMessagesWithThatCodeAreAboutThePassword() {
        val exceptions = listOf(
            // The same message with another code is some other failure.
            ArchiveException(Archive.ERRNO_FATAL, "Incorrect passphrase"),
            // The same code with another message too.
            ArchiveException(errnoMisc, "Truncated ZIP file data"),
            ArchiveException(errnoMisc, "incorrect passphrase")
        )
        for (exception in exceptions) {
            val mapped = exception.toFileSystemOrInterruptedIOException(file)

            assertSame(exception.message, FileSystemException::class.java, mapped.javaClass)
            mapped as FileSystemException
            assertEquals("/archive.zip", mapped.file)
            assertEquals(exception.message, mapped.reason)
            assertSame(exception, mapped.cause)
        }
    }

    @Test
    fun anyOtherFailureIsAnErrorOfTheArchiveFile() {
        val exception = ArchiveException(Archive.ERRNO_FATAL, "Unrecognized archive format")

        val mapped = exception.toFileSystemOrInterruptedIOException(file)

        assertSame(FileSystemException::class.java, mapped.javaClass)
        assertEquals("/archive.zip: Unrecognized archive format", mapped.message)
    }

    @Test
    fun aStreamPassesDataThroughUntouched() {
        val content = ByteArray(300) { it.toByte() }
        val stream = ArchiveExceptionInputStream(ByteArrayInputStream(content), file)

        assertEquals(0, stream.read())
        assertEquals(10L, stream.skip(10))
        assertEquals(289, stream.available())
        val rest = ByteArray(289)
        assertEquals(289, stream.read(rest))
        assertArrayEquals(content.copyOfRange(11, 300), rest)
        assertEquals(-1, stream.read(ByteArray(1), 0, 1))
        stream.close()
    }

    @Test
    fun everyOperationOfAStreamReportsWhatLibarchiveThrewForTheArchive() {
        val failing = object : InputStream() {
            override fun read(): Int = throw ArchiveException(errnoMisc, "Incorrect passphrase")

            override fun read(b: ByteArray, off: Int, len: Int): Int =
                throw ArchiveException(Archive.ERRNO_FATAL, "Damaged")

            override fun skip(n: Long): Long = throw ArchiveException(OsConstants.EINTR, "skip")

            override fun available(): Int = throw ArchiveException(Archive.ERRNO_FATAL, "Damaged")

            override fun reset() = throw ArchiveException(Archive.ERRNO_FATAL, "Damaged")

            override fun close() = throw ArchiveException(Archive.ERRNO_FATAL, "Damaged")
        }
        val stream = ArchiveExceptionInputStream(failing, file)

        assertThrows(ArchivePasswordRequiredException::class.java) { stream.read() }
        assertFileSystemException { stream.read(ByteArray(4)) }
        assertFileSystemException { stream.read(ByteArray(4), 1, 2) }
        assertThrows(InterruptedIOException::class.java) { stream.skip(1) }
        assertFileSystemException { stream.available() }
        assertFileSystemException { stream.reset() }
        assertFileSystemException { stream.close() }
    }

    @Test
    fun aFailureThatIsNotLibarchivesPassesThrough() {
        val failure = IOException("Connection reset")
        val stream = ArchiveExceptionInputStream(
            object : InputStream() {
                override fun read(): Int = throw failure
            },
            file
        )

        assertSame(failure, assertThrows(IOException::class.java) { stream.read() })
    }

    private fun assertFileSystemException(block: () -> Unit) {
        val exception = assertThrows(IOException::class.java) { block() }
        assertSame(FileSystemException::class.java, exception.javaClass)
        assertEquals("/archive.zip", (exception as FileSystemException).file)
        assertEquals("Damaged", exception.reason)
    }
}
