/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.archiver

import android.system.OsConstants
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.nio.charset.Charset
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * What [ReadArchive] does around libarchive without needing it: decoding the names of entries,
 * and answering the callbacks libarchive reads the archive through.
 */
class ReadArchiveCallbacksTest {
    @Test
    fun theUtf8NameLibarchiveFoundIsPreferredOverTheChosenCharset() {
        val shiftJis = Charset.forName("Shift_JIS")
        val bytes = "日本語.txt".toByteArray(shiftJis)

        assertEquals("日本語.txt", decodeEntryString("日本語.txt", bytes, Charsets.ISO_8859_1))
    }

    @Test
    fun withoutAUtf8NameTheBytesAreDecodedWithTheChosenCharset() {
        val shiftJis = Charset.forName("Shift_JIS")
        val bytes = "日本語.txt".toByteArray(shiftJis)

        assertEquals("日本語.txt", decodeEntryString(null, bytes, shiftJis))
        // The wrong charset gives the familiar garbage rather than failing.
        val cp437 = Charset.forName("IBM437")
        assertEquals(String(bytes, cp437), decodeEntryString(null, bytes, cp437))
        assertEquals("ä", decodeEntryString(null, byteArrayOf(0x84.toByte()), cp437))
    }

    @Test
    fun withNeitherThereIsNoString() {
        assertNull(decodeEntryString(null, null, Charsets.UTF_8))
        assertEquals("", decodeEntryString(null, ByteArray(0), Charsets.UTF_8))
    }

    @Test
    fun anInterruptedReadIsReportedAsAnInterruption() {
        val interruption = InterruptedIOException()

        val exception = interruption.toArchiveException("InputStream.read")

        assertEquals(OsConstants.EINTR, exception.code)
        assertEquals("InputStream.read", exception.message)
        assertSame(interruption, exception.cause)
        // A timeout is an InterruptedIOException too, and ends the read the same way.
        assertEquals(
            OsConstants.EINTR,
            SocketTimeoutException().toArchiveException("SeekableByteChannel.read").code
        )
    }

    @Test
    fun anyOtherFailedReadIsFatal() {
        val failure = IOException("Connection reset")

        val exception = failure.toArchiveException("SeekableByteChannel.position")

        assertEquals(Archive.ERRNO_FATAL, exception.code)
        assertEquals("SeekableByteChannel.position", exception.message)
        assertSame(failure, exception.cause)
    }

    @Test
    fun aSeekIsFromTheStartTheCurrentPositionOrTheEnd() {
        assertEquals(5L, seek(5, OsConstants.SEEK_SET))
        assertEquals(105L, seek(5, OsConstants.SEEK_CUR))
        assertEquals(990L, seek(-10, OsConstants.SEEK_END))
        assertEquals(1000L, seek(0, OsConstants.SEEK_END))
    }

    @Test
    fun aSeekOnlyAsksForWhatItNeeds() {
        // Asking a remote file for its size can be a request of its own.
        val asked = mutableListOf<String>()
        val position = {
            asked += "position"
            100L
        }
        val size = {
            asked += "size"
            1000L
        }

        seekPosition(5, OsConstants.SEEK_SET, position, size)
        assertEquals(emptyList<String>(), asked)
        seekPosition(5, OsConstants.SEEK_CUR, position, size)
        assertEquals(listOf("position"), asked)
        seekPosition(5, OsConstants.SEEK_END, position, size)
        assertEquals(listOf("position", "size"), asked)
    }

    @Test
    fun anUnknownWhenceIsFatal() {
        val exception = assertThrows(ArchiveException::class.java) { seek(0, 3) }
        assertEquals(Archive.ERRNO_FATAL, exception.code)
        assertEquals("Unknown whence 3", exception.message)
    }

    @Test
    fun aFailureToFindThePositionIsPassedOn() {
        val failure = IOException("Stream closed")
        val thrown = assertThrows(IOException::class.java) {
            seekPosition(0, OsConstants.SEEK_CUR, { throw failure }) { 0 }
        }
        assertSame(failure, thrown)
    }

    private fun seek(offset: Long, whence: Int): Long =
        seekPosition(offset, whence, { 100L }) { 1000L }
}
