/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.compat

import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel as JavaSeekableByteChannel
import java8.nio.channels.SeekableByteChannel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** [toJavaSeekableByteChannel] has to work for channels that only implement the java8 interface. */
class SeekableByteChannelCompatTest {
    @Test
    fun aChannelThatIsAlreadyAJavaOneIsReturnedAsItIs() {
        val channel = BothChannel()

        assertSame(channel, channel.toJavaSeekableByteChannel())
    }

    @Test
    fun aJava8OnlyChannelIsWrappedAndEveryMethodReachesIt() {
        val channel = Java8Channel("Hello".toByteArray())

        val javaChannel = channel.toJavaSeekableByteChannel()

        assertNotSame(channel as Any, javaChannel as Any)
        assertEquals(5L, javaChannel.size())
        val buffer = ByteBuffer.allocate(5)
        assertEquals(5, javaChannel.read(buffer))
        assertArrayEquals("Hello".toByteArray(), buffer.array())
        assertEquals(5L, javaChannel.position())
        assertSame(javaChannel, javaChannel.position(1))
        assertEquals(1L, javaChannel.position())
        assertEquals(2, javaChannel.write(ByteBuffer.wrap("ey".toByteArray())))
        assertArrayEquals("Heylo".toByteArray(), channel.bytes())
        assertSame(javaChannel, javaChannel.truncate(3))
        assertArrayEquals("Hey".toByteArray(), channel.bytes())
        assertTrue(javaChannel.isOpen)
        javaChannel.close()
        assertFalse(javaChannel.isOpen)
        assertFalse(channel.isOpen)
    }

    /** A channel that implements both interfaces, like the java8 backport's file channels do. */
    private class BothChannel :
        SeekableByteChannel,
        JavaSeekableByteChannel {
        override fun read(dst: ByteBuffer): Int = 0

        override fun write(src: ByteBuffer): Int = 0

        override fun position(): Long = 0

        override fun position(newPosition: Long): BothChannel = this

        override fun size(): Long = 0

        override fun truncate(size: Long): BothChannel = this

        override fun isOpen(): Boolean = true

        override fun close() {}
    }

    private class Java8Channel(bytes: ByteArray) : SeekableByteChannel {
        private var bytes = bytes.copyOf()
        private var position = 0L
        private var open = true

        fun bytes(): ByteArray = bytes.copyOf()

        override fun read(dst: ByteBuffer): Int {
            val count = minOf(dst.remaining().toLong(), size() - position).toInt()
            dst.put(bytes, position.toInt(), count)
            position += count
            return count
        }

        override fun write(src: ByteBuffer): Int {
            val count = src.remaining()
            val end = position.toInt() + count
            if (end > bytes.size) {
                bytes = bytes.copyOf(end)
            }
            src.get(bytes, position.toInt(), count)
            position += count
            return count
        }

        override fun position(): Long = position

        override fun position(newPosition: Long): Java8Channel {
            position = newPosition
            return this
        }

        override fun size(): Long = bytes.size.toLong()

        override fun truncate(size: Long): Java8Channel {
            if (size < bytes.size) {
                bytes = bytes.copyOf(size.toInt())
            }
            if (position > size) {
                position = size
            }
            return this
        }

        override fun isOpen(): Boolean = open

        override fun close() {
            open = false
        }
    }
}
