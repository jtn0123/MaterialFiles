/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import java.io.IOException
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ByteBufferInputStream], which feeds a buffer to an API that only takes streams. */
class ByteBufferInputStreamTest {
    private fun stream(vararg bytes: Int) =
        ByteBufferInputStream(ByteBuffer.wrap(ByteArray(bytes.size) { bytes[it].toByte() }))

    @Test
    fun bytesAreReadAsUnsignedValuesUntilTheEnd() {
        val stream = stream(0x01, 0xFF)
        assertEquals(2, stream.available())
        assertEquals(0x01, stream.read())
        assertEquals(0xFF, stream.read())
        assertEquals(-1, stream.read())
        assertEquals(0, stream.available())
    }

    @Test
    fun aBulkReadTakesWhatIsLeftAndThenReportsTheEnd() {
        val stream = stream(1, 2, 3)
        val bytes = ByteArray(5)
        assertEquals(0, stream.read(bytes, 0, 0))
        assertEquals(3, stream.read(bytes, 1, 4))
        assertArrayEquals(byteArrayOf(0, 1, 2, 3, 0), bytes)
        assertEquals(-1, stream.read(bytes, 0, 5))
        // Asking for nothing is not the end, even at the end.
        assertEquals(0, stream.read(bytes, 0, 0))
    }

    @Test
    fun onlyWhatIsLeftCanBeSkippedAndNothingBackwards() {
        val stream = stream(1, 2, 3, 4)
        assertEquals(0, stream.skip(0))
        assertEquals(0, stream.skip(-5))
        assertEquals(2, stream.skip(2))
        assertEquals(3, stream.read())
        assertEquals(1, stream.skip(10))
        assertEquals(-1, stream.read())
    }

    @Test
    fun aSkipBeyondTheIntRangeStillStopsAtTheEnd() {
        val stream = stream(1, 2, 3, 4)
        assertEquals(1, stream.read())
        assertEquals(3, stream.skip(Long.MAX_VALUE))
        assertEquals(-1, stream.read())
        val other = stream(1, 2, 3, 4)
        // 2^32 + 2 would wrap to 2 if it were cut down to an Int first.
        assertEquals(4, other.skip((1L shl 32) + 2))
    }

    @Test
    fun aMarkedPositionCanBeReturnedTo() {
        val stream = stream(1, 2, 3)
        assertTrue(stream.markSupported())
        stream.read()
        stream.mark(0)
        assertEquals(2, stream.read())
        assertEquals(3, stream.read())
        stream.reset()
        assertEquals(2, stream.read())
    }

    @Test
    fun aClosedStreamRefusesEverything() {
        val stream = stream(1, 2)
        stream.close()
        stream.close()
        assertThrows(IOException::class.java) { stream.read() }
        assertThrows(IOException::class.java) { stream.read(ByteArray(1), 0, 1) }
        assertThrows(IOException::class.java) { stream.skip(1) }
        assertThrows(IOException::class.java) { stream.available() }
        assertThrows(IOException::class.java) { stream.mark(0) }
        assertThrows(IOException::class.java) { stream.reset() }
    }

    @Test
    fun readingConsumesTheBufferItself() {
        val buffer = ByteBuffer.wrap(byteArrayOf(1, 2, 3))
        ByteBufferInputStream(buffer).use { it.read(ByteArray(2)) }
        assertEquals(2, buffer.position())
    }
}
