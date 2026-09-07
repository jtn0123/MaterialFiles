/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.sftp.client

import java.io.IOException
import java.io.OutputStream
import java.util.ArrayDeque

/**
 * Where a [PipelinedOutputStream] sends its data: something that can start a write and hand back
 * a handle to wait on later, so that several writes can be in flight at once.
 */
internal interface PipelinedWriteTarget {
    /** The largest [PipelinedWriteTarget.writeAsync] length the target accepts. */
    val maxWriteSize: Int

    /**
     * Starts writing `data[offset, offset + length)` at [fileOffset]. The bytes are copied before
     * this returns, so the caller may reuse its buffer. The returned [PendingWrite] reports the
     * outcome.
     */
    @Throws(IOException::class)
    fun writeAsync(fileOffset: Long, data: ByteArray, offset: Int, length: Int): PendingWrite

    /** Closes the target; called once, after every pending write has been waited on. */
    @Throws(IOException::class)
    fun close()
}

internal fun interface PendingWrite {
    /** Waits for the write to finish; throws when it failed. */
    @Throws(IOException::class)
    fun await()
}

/**
 * An [OutputStream] that keeps up to [maxPendingWrites] writes in flight instead of waiting for
 * each one to be acknowledged before sending the next. On a link with a long round trip this is
 * the difference between one packet per round trip and a full pipe.
 *
 * The first failure is remembered and rethrown from every later call, including [close], so a
 * caller that only checks the result of [close] still sees it. Every remembered failure is also
 * given later failures as suppressed exceptions.
 */
internal class PipelinedOutputStream(
    private val target: PipelinedWriteTarget,
    private val maxPendingWrites: Int = DEFAULT_MAX_PENDING_WRITES
) : OutputStream() {
    private val pendingWrites = ArrayDeque<PendingWrite>(maxPendingWrites)
    private val singleByte = ByteArray(1)

    private var fileOffset = 0L
    private var failure: IOException? = null
    private var isClosed = false

    init {
        require(maxPendingWrites > 0) { "maxPendingWrites must be positive" }
        require(target.maxWriteSize > 0) { "maxWriteSize must be positive" }
    }

    @Throws(IOException::class)
    override fun write(value: Int) {
        singleByte[0] = value.toByte()
        write(singleByte, 0, 1)
    }

    @Throws(IOException::class)
    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        ensureOpen()
        if (offset < 0 || length < 0 || offset > buffer.size - length) {
            throw IndexOutOfBoundsException()
        }
        throwFailure()
        var currentOffset = offset
        var remaining = length
        while (remaining > 0) {
            if (pendingWrites.size == maxPendingWrites) {
                awaitOldestWrite()
                throwFailure()
            }
            val writeSize = remaining.coerceAtMost(target.maxWriteSize)
            val pendingWrite = try {
                target.writeAsync(fileOffset, buffer, currentOffset, writeSize)
            } catch (e: IOException) {
                recordFailure(e)
                throw e
            }
            pendingWrites.addLast(pendingWrite)
            fileOffset += writeSize
            currentOffset += writeSize
            remaining -= writeSize
        }
    }

    @Throws(IOException::class)
    override fun flush() {
        ensureOpen()
        awaitAllWrites()
        throwFailure()
    }

    @Throws(IOException::class)
    override fun close() {
        if (isClosed) {
            return
        }
        isClosed = true
        awaitAllWrites()
        try {
            target.close()
        } catch (e: IOException) {
            recordFailure(e)
        }
        throwFailure()
    }

    private fun awaitAllWrites() {
        while (pendingWrites.isNotEmpty()) {
            awaitOldestWrite()
        }
    }

    private fun awaitOldestWrite() {
        val pendingWrite = pendingWrites.removeFirst()
        try {
            pendingWrite.await()
        } catch (e: IOException) {
            recordFailure(e)
        }
    }

    private fun recordFailure(exception: IOException) {
        val failure = failure
        if (failure == null) {
            this.failure = exception
        } else if (failure !== exception) {
            failure.addSuppressed(exception)
        }
    }

    @Throws(IOException::class)
    private fun throwFailure() {
        failure?.let { throw it }
    }

    @Throws(IOException::class)
    private fun ensureOpen() {
        if (isClosed) {
            throw IOException("Stream closed")
        }
    }

    companion object {
        /**
         * Eight writes of the SFTP packet size (32 KiB on OpenSSH) is 256 KiB in flight, enough
         * to fill a 20 Mbit/s link with a 100 ms round trip without holding much memory.
         */
        const val DEFAULT_MAX_PENDING_WRITES = 8
    }
}
