/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The copy every file transfer runs through: it reports progress and stops when cancelled. */
class InputStreamExtensionsTest {
    @Test
    fun everyByteArrivesAndTheProgressAddsUpToTheSize() {
        val content = ByteArray(DEFAULT_BUFFER_SIZE * 2 + 5) { it.toByte() }
        val outputStream = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()
        ByteArrayInputStream(content).copyTo(outputStream, 0) { progress += it }
        assertArrayEquals(content, outputStream.toByteArray())
        assertEquals(content.size.toLong(), progress.sum())
        // Reported while copying, not only at the end.
        assertTrue(progress.size > 1)
    }

    @Test
    fun anIntervalKeepsTheReportsDownToTheLastOne() {
        val content = ByteArray(DEFAULT_BUFFER_SIZE * 2)
        val progress = mutableListOf<Long>()
        ByteArrayInputStream(content)
            .copyTo(ByteArrayOutputStream(), TimeUnit.MINUTES.toMillis(10)) { progress += it }
        assertEquals(listOf(content.size.toLong()), progress)
    }

    @Test
    fun aCopyWithoutAListenerIsStillACopy() {
        val outputStream = ByteArrayOutputStream()
        ByteArrayInputStream("hello".toByteArray()).copyTo(outputStream, 0, null)
        assertEquals("hello", outputStream.toString())
    }

    @Test
    fun aCancelledCopyStopsWhereItIs() {
        val content = ByteArray(DEFAULT_BUFFER_SIZE * 4)
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedIOException::class.java) {
                ByteArrayInputStream(content).copyTo(ByteArrayOutputStream(), 0, null)
            }
        } finally {
            // copyTo cleared the flag by testing it; make sure the thread is clean either way.
            Thread.interrupted()
        }
    }

    @Test
    fun readFullyKeepsReadingUntilTheBufferIsFull() {
        val content = ByteArray(10) { it.toByte() }
        val buffer = ByteArray(10)
        val readSize = OneByteAtATimeInputStream(content).readFully(buffer, 0, buffer.size)
        assertEquals(10, readSize)
        assertArrayEquals(content, buffer)
    }

    @Test
    fun readFullyReportsWhatItGotWhenTheFileEnds() {
        val content = ByteArray(4) { it.toByte() }
        val buffer = ByteArray(10)
        val readSize = OneByteAtATimeInputStream(content).readFully(buffer, 2, 8)
        assertEquals(4, readSize)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 2, 3, 0, 0, 0, 0), buffer)
    }

    /** A stream like a slow network one, which answers a read with less than was asked for. */
    private class OneByteAtATimeInputStream(private val content: ByteArray) : InputStream() {
        private var position = 0

        override fun read(): Int = if (position < content.size) {
            content[position++].toInt() and 0xFF
        } else {
            -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position >= content.size) {
                return -1
            }
            if (len == 0) {
                return 0
            }
            b[off] = content[position++]
            return 1
        }
    }
}
