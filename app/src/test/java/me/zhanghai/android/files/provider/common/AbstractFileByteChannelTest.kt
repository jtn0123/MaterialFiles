/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AbstractFileByteChannelTest {
    private class Channel(
        private val content: ByteArray,
        private val stalledReads: Int = 0,
        readTimeoutMillis: Long = 15_000
    ) : AbstractFileByteChannel(false, readTimeoutMillis = readTimeoutMillis) {
        val requests = mutableListOf<Pair<Long, Int>>()
        val stalledFutures = mutableListOf<Future<ByteBuffer>>()

        override fun onReadAsync(
            position: Long,
            size: Int,
            timeoutMillis: Long
        ): Future<ByteBuffer> {
            synchronized(requests) {
                requests += position to size
                if (stalledFutures.size < stalledReads) {
                    // A server that never answers.
                    return CompletableFuture<ByteBuffer>().also { stalledFutures += it }
                }
            }
            val start = position.coerceAtMost(content.size.toLong()).toInt()
            val end = (start + size).coerceAtMost(content.size)
            return CompletableFuture.completedFuture(ByteBuffer.wrap(content, start, end - start))
        }

        override fun onWrite(position: Long, source: ByteBuffer) = throw AssertionError()

        override fun onTruncate(size: Long) = throw AssertionError()

        override fun onSize(): Long = content.size.toLong()
    }

    private val content = ByteArray(3 * 1024 * 1024) { (it * 31).toByte() }

    @Test
    fun readingTheStartOfAFileAsksForLittleAndNothingAhead() {
        val channel = Channel(content)
        val header = ByteBuffer.allocate(64 * 1024)
        while (header.hasRemaining()) {
            channel.read(header)
        }
        assertEquals(listOf(0L to 128 * 1024), channel.requests)
        assertArrayEquals(content.copyOf(64 * 1024), header.array())
    }

    @Test
    fun readingOnGetsEverythingInOrder() {
        val channel = Channel(content)
        val destination = ByteBuffer.allocate(content.size + 1)
        while (channel.read(destination) != -1) {
            // Keep reading.
        }
        assertArrayEquals(content, destination.array().copyOf(destination.position()))
        assertEquals(0L to 128 * 1024, channel.requests[0])
        assertEquals(128L * 1024 to 1024 * 1024, channel.requests[1])
    }

    @Test
    fun seekingAfterTheFirstReadStillReadsTheRightBytes() {
        val channel = Channel(content)
        channel.read(ByteBuffer.allocate(16))
        channel.position(2_000_000)
        val destination = ByteBuffer.allocate(1000)
        while (destination.hasRemaining()) {
            channel.read(destination)
        }
        assertArrayEquals(content.copyOfRange(2_000_000, 2_001_000), destination.array())
    }

    @Test
    fun aReadThatNeverCompletesTimesOutAndIsAbandoned() {
        val channel = Channel(content, stalledReads = 1, readTimeoutMillis = 50)
        assertThrows(SocketTimeoutException::class.java) {
            channel.read(ByteBuffer.allocate(16))
        }
        assertTrue(channel.stalledFutures.single().isCancelled)
        assertEquals(0L, channel.position())
    }

    @Test
    fun aReadAfterATimeoutAsksAgainAndIsStillSmall() {
        val channel = Channel(content, stalledReads = 1, readTimeoutMillis = 50)
        assertThrows(SocketTimeoutException::class.java) {
            channel.read(ByteBuffer.allocate(16))
        }
        val destination = ByteBuffer.allocate(16)
        channel.read(destination)
        assertArrayEquals(content.copyOf(16), destination.array())
        assertEquals(listOf(0L to 128 * 1024, 0L to 128 * 1024), channel.requests)
    }
}
