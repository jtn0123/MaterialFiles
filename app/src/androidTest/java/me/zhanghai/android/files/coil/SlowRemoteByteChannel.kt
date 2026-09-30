/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.coil

import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.ClosedByInterruptException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import me.zhanghai.android.files.provider.common.AbstractFileByteChannel
import me.zhanghai.android.files.util.closeSafe

/**
 * A channel over a local file that is read the way the SMB channel reads a file on a share: each
 * chunk is a positioned request ([onRead]) that takes [RemoteReads.latencyMillis] to answer, or
 * does not answer at all while [RemoteReads.block] holds the file.
 *
 * Waiting for an answer also behaves like SMBJ's: the wait happens on the thread that holds the
 * channel's I/O lock, a wait that is interrupted closes the channel, and a wait whose channel is
 * closed from another thread fails with [AsynchronousCloseException] and marks the channel closed.
 * Both take the channel's close lock while its I/O lock is held, which is the interleaving that
 * once deadlocked thumbnail reads that were being abandoned.
 */
class SlowRemoteByteChannel(private val file: File, private val reads: RemoteReads) :
    AbstractFileByteChannel(false) {
    private val name = file.name

    private val randomAccessFile = RandomAccessFile(file, "r")

    init {
        reads.onChannelOpened(name)
    }

    override fun onReadAsync(position: Long, size: Int, timeoutMillis: Long): Future<ByteBuffer> {
        // The request is on its way at once, as SMB's is.
        val request = super.onReadAsync(position, size, timeoutMillis)
        return object : Future<ByteBuffer> by request {
            override fun get(): ByteBuffer = awaitAnswer(request, Long.MAX_VALUE)

            override fun get(timeout: Long, unit: TimeUnit): ByteBuffer =
                awaitAnswer(request, unit.toMillis(timeout))
        }
    }

    private fun awaitAnswer(request: Future<ByteBuffer>, timeoutMillis: Long): ByteBuffer {
        val startMillis = SystemClock.elapsedRealtime()
        while (true) {
            try {
                return request.get(POLL_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                // Not answered yet.
            } catch (e: InterruptedException) {
                request.cancel(true)
                // SMB's channel closes itself when a wait for its server is interrupted.
                closeSafe()
                throw ExecutionException(ClosedByInterruptException().apply { initCause(e) })
            }
            // Closing a handle fails the reads still outstanding on it.
            if (!isOpen) {
                request.cancel(true)
                setClosed()
                throw ExecutionException(AsynchronousCloseException())
            }
            if (SystemClock.elapsedRealtime() - startMillis >= timeoutMillis) {
                throw TimeoutException()
            }
        }
    }

    @Throws(IOException::class)
    override fun onRead(position: Long, size: Int): ByteBuffer = reads.trackRead(name) {
        // Not through a FileChannel, which an interrupted read (a cancelled read-ahead) closes.
        val bytes = ByteArray(size)
        var length = 0
        synchronized(randomAccessFile) {
            randomAccessFile.seek(position)
            while (length < size) {
                val count = randomAccessFile.read(bytes, length, size - length)
                if (count == -1) {
                    break
                }
                length += count
            }
        }
        ByteBuffer.wrap(bytes, 0, length)
    }

    override fun onWrite(position: Long, source: ByteBuffer) =
        throw IOException("$file is read-only")

    override fun onTruncate(size: Long) = throw IOException("$file is read-only")

    override fun onSize(): Long = file.length()

    override fun onClose() {
        randomAccessFile.closeSafe()
        reads.onChannelClosed(name)
    }

    companion object {
        private const val POLL_INTERVAL_MILLIS = 10L
    }
}

/**
 * What was read from a [SlowRemoteFileSystem], per file name and across all its channels, and the
 * knobs that make the reads slow or stuck.
 */
class RemoteReads {
    /** How long each read takes to be answered. */
    @Volatile
    var latencyMillis = 0L

    private val lock = Object()

    private val bytesRead = mutableMapOf<String, Long>()
    private val readCounts = mutableMapOf<String, Int>()
    private val openedChannels = mutableMapOf<String, Int>()
    private val closedChannels = mutableMapOf<String, Int>()
    private val blockedFiles = mutableMapOf<String, CountDownLatch>()
    private var readsInFlight = 0
    private var openChannels = 0
    private var blockedReads = 0

    var peakReadsInFlight = 0
        get() = synchronized(lock) { field }
        private set

    var peakOpenChannels = 0
        get() = synchronized(lock) { field }
        private set

    /** Reads of [name] go unanswered until [unblockAll], as if the server stopped responding. */
    fun block(name: String) {
        synchronized(lock) { blockedFiles[name] = CountDownLatch(1) }
    }

    fun unblockAll() {
        val latches = synchronized(lock) {
            blockedFiles.values.toList().also { blockedFiles.clear() }
        }
        latches.forEach { it.countDown() }
    }

    fun bytesRead(name: String): Long = synchronized(lock) { bytesRead[name] ?: 0 }

    fun readCount(name: String): Int = synchronized(lock) { readCounts[name] ?: 0 }

    fun openedChannels(name: String): Int = synchronized(lock) { openedChannels[name] ?: 0 }

    fun closedChannels(name: String): Int = synchronized(lock) { closedChannels[name] ?: 0 }

    val openChannelCount: Int
        get() = synchronized(lock) { openChannels }

    val blockedReadCount: Int
        get() = synchronized(lock) { blockedReads }

    val readsInFlightCount: Int
        get() = synchronized(lock) { readsInFlight }

    fun resetPeaks() {
        synchronized(lock) {
            peakReadsInFlight = readsInFlight
            peakOpenChannels = openChannels
        }
    }

    internal fun onChannelOpened(name: String) {
        synchronized(lock) {
            openedChannels.increment(name)
            ++openChannels
            peakOpenChannels = maxOf(peakOpenChannels, openChannels)
        }
    }

    internal fun onChannelClosed(name: String) {
        synchronized(lock) {
            closedChannels.increment(name)
            --openChannels
        }
    }

    /** Runs a positioned read of [name], after the latency and whatever block there is. */
    internal fun trackRead(name: String, read: () -> ByteBuffer): ByteBuffer {
        val gate = synchronized(lock) {
            readCounts.increment(name)
            ++readsInFlight
            peakReadsInFlight = maxOf(peakReadsInFlight, readsInFlight)
            blockedFiles[name]
        }
        try {
            if (latencyMillis > 0) {
                Thread.sleep(latencyMillis)
            }
            if (gate != null) {
                synchronized(lock) { ++blockedReads }
                try {
                    gate.await()
                } finally {
                    synchronized(lock) { --blockedReads }
                }
            }
            val buffer = read()
            synchronized(lock) {
                bytesRead[name] = (bytesRead[name] ?: 0) + buffer.remaining()
            }
            return buffer
        } finally {
            synchronized(lock) { --readsInFlight }
        }
    }

    private fun MutableMap<String, Int>.increment(key: String) {
        this[key] = (this[key] ?: 0) + 1
    }
}
