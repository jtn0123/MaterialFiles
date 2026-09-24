/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.sftp.client

import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelinedOutputStreamTest {
    /**
     * A target that records every write and lets a test decide which ones fail. Writes complete
     * when awaited, which is how a real target behaves once the round trip is over.
     */
    private class RecordingTarget(
        override val maxWriteSize: Int = 4,
        private val failWriteAt: Long? = null,
        private val failAwaitAt: Long? = null,
        private val failClose: Boolean = false
    ) : PipelinedWriteTarget {
        val content = java.io.ByteArrayOutputStream()
        val offsets = mutableListOf<Long>()
        var outstanding = 0
            private set
        var maxOutstanding = 0
            private set
        var isClosed = false
            private set

        override fun writeAsync(
            fileOffset: Long,
            data: ByteArray,
            offset: Int,
            length: Int
        ): PendingWrite {
            assertFalse("write after close", isClosed)
            if (fileOffset == failWriteAt) {
                throw IOException("write failed at $fileOffset")
            }
            assertEquals("writes must be contiguous", content.size().toLong(), fileOffset)
            content.write(data, offset, length)
            offsets += fileOffset
            ++outstanding
            maxOutstanding = maxOf(maxOutstanding, outstanding)
            return PendingWrite {
                --outstanding
                if (fileOffset == failAwaitAt) {
                    throw IOException("await failed at $fileOffset")
                }
            }
        }

        override fun close() {
            assertEquals("close before drain", 0, outstanding)
            isClosed = true
            if (failClose) {
                throw IOException("close failed")
            }
        }
    }

    @Test
    fun writesAreChunkedToMaxWriteSizeAndStayContiguous() {
        val target = RecordingTarget(maxWriteSize = 4)
        val data = ByteArray(11) { it.toByte() }
        PipelinedOutputStream(target, 8).use {
            it.write(data, 0, 5)
            it.write(data, 5, 6)
            it.write(0x7F)
        }
        assertArrayEquals(data + 0x7F.toByte(), target.content.toByteArray())
        assertEquals(listOf(0L, 4L, 5L, 9L, 11L), target.offsets)
        assertTrue(target.isClosed)
    }

    @Test
    fun keepsAtMostMaxPendingWritesInFlight() {
        val target = RecordingTarget(maxWriteSize = 1)
        PipelinedOutputStream(target, 3).use { it.write(ByteArray(10)) }
        assertEquals(3, target.maxOutstanding)
        assertEquals(10, target.content.size())
    }

    @Test
    fun flushDrainsEveryPendingWrite() {
        val target = RecordingTarget(maxWriteSize = 1)
        val stream = PipelinedOutputStream(target, 8)
        stream.write(ByteArray(5))
        assertEquals(5, target.outstanding)
        stream.flush()
        assertEquals(0, target.outstanding)
        stream.close()
    }

    @Test
    fun failureOfAnEarlierWriteSurfacesOnTheNextCallAndAgainOnClose() {
        val target = RecordingTarget(maxWriteSize = 1, failAwaitAt = 1)
        val stream = PipelinedOutputStream(target, 2)
        // Writes 0 and 1 are in flight; the third write must wait for write 0, the fourth for
        // write 1, which fails.
        stream.write(ByteArray(3))
        val exception = assertThrows(IOException::class.java) { stream.write(ByteArray(1)) }
        assertEquals("await failed at 1", exception.message)
        assertSame(exception, assertThrows(IOException::class.java) { stream.write(0) })
        assertSame(exception, assertThrows(IOException::class.java) { stream.close() })
        assertTrue("the remote handle is closed even after a failure", target.isClosed)
    }

    @Test
    fun failureToStartAWriteIsThrownImmediately() {
        val target = RecordingTarget(maxWriteSize = 4, failWriteAt = 4)
        val stream = PipelinedOutputStream(target, 8)
        val exception = assertThrows(IOException::class.java) { stream.write(ByteArray(8)) }
        assertEquals("write failed at 4", exception.message)
        assertSame(exception, assertThrows(IOException::class.java) { stream.flush() })
    }

    @Test
    fun laterFailuresAreSuppressedIntoTheFirst() {
        val target = RecordingTarget(maxWriteSize = 1, failAwaitAt = 0, failClose = true)
        val stream = PipelinedOutputStream(target, 8)
        stream.write(ByteArray(1))
        val exception = assertThrows(IOException::class.java) { stream.close() }
        assertEquals("await failed at 0", exception.message)
        assertEquals(listOf("close failed"), exception.suppressed.map { it.message })
    }

    @Test
    fun closeIsIdempotentAndWriteAfterCloseFails() {
        val target = RecordingTarget()
        val stream = PipelinedOutputStream(target)
        stream.close()
        stream.close()
        assertThrows(IOException::class.java) { stream.write(1) }
        assertThrows(IOException::class.java) { stream.flush() }
    }

    @Test
    fun rejectsBadRanges() {
        val stream = PipelinedOutputStream(RecordingTarget())
        assertThrows(IndexOutOfBoundsException::class.java) { stream.write(ByteArray(2), 1, 2) }
        assertThrows(IndexOutOfBoundsException::class.java) { stream.write(ByteArray(2), -1, 1) }
    }
}
