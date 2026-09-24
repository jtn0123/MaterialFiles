/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.archiver

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The streams of an entry's data, against stand-ins for `Archive.readData` and
 * `Archive.writeData` that treat the buffer the way the JNI code does: from its position to its
 * limit, moving the position past what was transferred, and at most [CHUNK] bytes at a time, as
 * libarchive hands out decompressed data in blocks.
 */
class ArchiveDataStreamsTest {
    private val content = ByteArray(1000) { (it * 7).toByte() }

    private var readOffset = 0

    private fun readData(buffer: ByteBuffer) {
        val count = minOf(CHUNK, buffer.remaining(), content.size - readOffset)
        buffer.put(content, readOffset, count)
        readOffset += count
    }

    private val written = ByteArrayOutputStream()

    private fun writeData(buffer: ByteBuffer) {
        val bytes = ByteArray(minOf(CHUNK, buffer.remaining()))
        buffer.get(bytes)
        written.write(bytes)
    }

    @Test
    fun anEntryIsReadWholeThroughAnArrayOfItsOwn() {
        val stream = ArchiveDataInputStream(::readData)
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(256)
        while (true) {
            val count = stream.read(buffer)
            if (count == -1) {
                break
            }
            // Never more than libarchive had, and never nothing before the end.
            assertTrue("$count", count in 1..CHUNK)
            output.write(buffer, 0, count)
        }

        assertArrayEquals(content, output.toByteArray())
        assertEquals(-1, stream.read(buffer))
    }

    @Test
    fun singleBytesAreUnsignedAndTheEndIsMinusOne() {
        val stream = ArchiveDataInputStream(::readData)

        assertEquals(0, stream.read())
        assertEquals(7, stream.read())
        repeat(content.size - 2) { stream.read() }
        assertEquals(-1, stream.read())
        val high = ArchiveDataInputStream { it.put(0xFE.toByte()) }
        assertEquals(0xFE, high.read())
    }

    @Test
    fun whatLibarchiveThrowsWhileReadingIsPassedOn() {
        val failure = ArchiveException(Archive.ERRNO_FATAL, "Truncated input")
        val stream = ArchiveDataInputStream { throw failure }

        assertSame(failure, assertThrows(ArchiveException::class.java) { stream.read() })
        assertSame(
            failure,
            assertThrows(ArchiveException::class.java) { stream.read(ByteArray(8)) }
        )
    }

    @Test
    fun anArrayIsWrittenWholeEvenWhenLibarchiveTakesItInPieces() {
        val stream = ArchiveDataOutputStream(::writeData)

        stream.write(content)
        stream.write(content, 10, 20)

        assertArrayEquals(content + content.copyOfRange(10, 30), written.toByteArray())
    }

    @Test
    fun whatLibarchiveThrowsWhileWritingIsPassedOn() {
        val failure = ArchiveException(Archive.ERRNO_FATAL, "Write failed")
        val stream = ArchiveDataOutputStream { throw failure }

        assertSame(
            failure,
            assertThrows(ArchiveException::class.java) { stream.write(ByteArray(8)) }
        )
    }

    companion object {
        private const val CHUNK = 64
    }
}
