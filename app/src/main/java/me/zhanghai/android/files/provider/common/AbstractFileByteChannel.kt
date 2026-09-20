/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.NonReadableChannelException
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java8.nio.channels.SeekableByteChannel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import me.zhanghai.android.files.util.closeSafe

/**
 * A seekable channel over a file that is read and written in positioned chunks, for providers
 * whose protocol offers "read N bytes at offset" (SFTP, SMB, WebDAV ranges, FTP with REST)
 * rather than a stream.
 *
 * Reads go through a read-ahead buffer: [onReadAsync] is asked for the next chunk as soon as the
 * previous one was consumed, so sequential reads overlap the network round trip. When the caller
 * seeks elsewhere the pending read is cancelled ([shouldCancelRead]) and optionally joined
 * ([joinCancelledRead]) for protocols whose connection cannot carry two requests. Writes are
 * synchronous through [onWrite], or [onAppend] when opened in append mode, in which case reading
 * is refused. [position], [size] and [truncate] are bookkept locally; subclasses implement
 * [onSize] and [onTruncate] against the server. All I/O is serialised on one lock, so one channel
 * is safe to share between threads but never concurrent.
 *
 * A read that has not completed within [readTimeoutMillis] of being waited for fails with a
 * [SocketTimeoutException] and is abandoned (cancelled too, if [shouldCancelRead]); a later read
 * asks again at the same position.
 */
abstract class AbstractFileByteChannel(
    private val isAppend: Boolean,
    private val shouldCancelRead: Boolean = true,
    private val joinCancelledRead: Boolean = false,
    private val readTimeoutMillis: Long = READ_TIMEOUT_MILLIS
) : ForceableChannel,
    SeekableByteChannel {
    private var position = 0L
    private val readBuffer = ReadBuffer()
    private val ioLock = Any()

    private var isOpen = true
    private val closeLock = Any()

    @Throws(IOException::class)
    final override fun read(destination: ByteBuffer): Int {
        ensureOpen()
        if (isAppend) {
            throw NonReadableChannelException()
        }
        val remaining = destination.remaining()
        if (remaining == 0) {
            return 0
        }
        return synchronized(ioLock) {
            readBuffer.read(destination).also {
                if (it != -1) {
                    position += it
                }
            }
        }
    }

    /** The dispatcher [onReadAsync] reads on; overridable so that tests can run it eagerly. */
    protected open val readDispatcher: CoroutineDispatcher
        get() = Dispatchers.IO

    protected open fun onReadAsync(
        position: Long,
        size: Int,
        timeoutMillis: Long
    ): Future<ByteBuffer> =
        @OptIn(DelicateCoroutinesApi::class)
        GlobalScope.async(readDispatcher) {
            withTimeout(timeoutMillis) {
                runInterruptible {
                    onRead(position, size)
                }
            }
        }
            .asFuture()

    @Throws(IOException::class)
    protected open fun onRead(position: Long, size: Int): ByteBuffer = throw NotImplementedError()

    @Throws(IOException::class)
    final override fun write(source: ByteBuffer): Int {
        ensureOpen()
        val remaining = source.remaining()
        if (remaining == 0) {
            return 0
        }
        synchronized(ioLock) {
            if (isAppend) {
                onAppend(source)
                position = onSize()
            } else {
                onWrite(position, source)
                position += remaining - source.remaining()
            }
            return remaining
        }
    }

    @Throws(IOException::class)
    protected abstract fun onWrite(position: Long, source: ByteBuffer)

    @Throws(IOException::class)
    protected open fun onAppend(source: ByteBuffer) {
        val position = onSize()
        onWrite(position, source)
    }

    @Throws(IOException::class)
    final override fun position(): Long {
        ensureOpen()
        synchronized(ioLock) {
            if (isAppend) {
                position = onSize()
            }
            return position
        }
    }

    final override fun position(newPosition: Long): SeekableByteChannel {
        ensureOpen()
        if (isAppend) {
            // Ignored.
            return this
        }
        synchronized(ioLock) {
            readBuffer.reposition(position, newPosition)
            position = newPosition
        }
        return this
    }

    @Throws(IOException::class)
    final override fun size(): Long {
        ensureOpen()
        return onSize()
    }

    @Throws(IOException::class)
    final override fun truncate(size: Long): SeekableByteChannel {
        ensureOpen()
        require(size >= 0)
        synchronized(ioLock) {
            val currentSize = onSize()
            if (size >= currentSize) {
                return this
            }
            onTruncate(size)
            position = position.coerceAtMost(size)
        }
        return this
    }

    @Throws(IOException::class)
    protected abstract fun onTruncate(size: Long)

    @Throws(IOException::class)
    protected abstract fun onSize(): Long

    @Throws(IOException::class)
    final override fun force(metaData: Boolean) {
        ensureOpen()
        synchronized(ioLock) {
            onForce(metaData)
        }
    }

    @Throws(IOException::class)
    protected open fun onForce(metaData: Boolean) {}

    @Throws(ClosedChannelException::class)
    private fun ensureOpen() {
        synchronized(closeLock) {
            if (!isOpen) {
                throw ClosedChannelException()
            }
        }
    }

    final override fun isOpen(): Boolean = synchronized(closeLock) { isOpen }

    @Throws(IOException::class)
    final override fun close() {
        synchronized(closeLock) {
            if (!isOpen) {
                return
            }
            isOpen = false
            synchronized(ioLock) {
                readBuffer.closeSafe()
                onClose()
            }
        }
    }

    protected fun setClosed() {
        synchronized(closeLock) {
            isOpen = false
        }
    }

    @Throws(IOException::class)
    protected open fun onClose() {}

    private inner class ReadBuffer : Closeable {
        private val buffer = ByteBuffer.allocate(BUFFER_SIZE).apply { limit(0) }
        private var bufferedPosition = 0L

        private var pendingRead: Future<ByteBuffer>? = null
        private val pendingReadLock = Any()

        // Many readers only want what is at the start of a file (its type, its metadata, an
        // embedded thumbnail), so the first read is small and nothing is read ahead until a
        // second one shows that the file is being read through.
        private var isFirstRead = true

        @Throws(IOException::class)
        fun read(destination: ByteBuffer): Int {
            if (!buffer.hasRemaining()) {
                readIntoBuffer()
                if (!buffer.hasRemaining()) {
                    return -1
                }
            }
            val length = destination.remaining().coerceAtMost(buffer.remaining())
            val bufferLimit = buffer.limit()
            buffer.limit(buffer.position() + length)
            destination.put(buffer)
            buffer.limit(bufferLimit)
            return length
        }

        @Throws(IOException::class)
        private fun readIntoBuffer() {
            val isFirstRead = isFirstRead
            val future = synchronized(pendingReadLock) {
                pendingRead?.also { pendingRead = null }
            } ?: readIntoBufferAsync(if (isFirstRead) FIRST_READ_SIZE else BUFFER_SIZE)
            val newBuffer = try {
                // Not every protocol's future honours the timeout it was given (SMBJ's async read
                // bypasses its own), and a connection that died silently never answers.
                future.get(readTimeoutMillis, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                abandonRead(future)
                throw SocketTimeoutException("Read timed out after $readTimeoutMillis ms")
                    .apply { initCause(e) }
            } catch (e: CancellationException) {
                throw InterruptedIOException().apply { initCause(e) }
            } catch (e: InterruptedException) {
                throw InterruptedIOException().apply { initCause(e) }
            } catch (e: ExecutionException) {
                val exception = e.cause ?: e
                if (exception is IOException) {
                    throw exception
                } else {
                    throw IOException(exception)
                }
            }
            // Only now, so that a retry after a failed first read is still a small one.
            this.isFirstRead = false
            buffer.clear()
            buffer.put(newBuffer)
            buffer.flip()
            if (!buffer.hasRemaining()) {
                return
            }
            bufferedPosition += buffer.remaining()
            if (isFirstRead) {
                return
            }
            synchronized(pendingReadLock) {
                pendingRead = readIntoBufferAsync(BUFFER_SIZE)
            }
        }

        private fun readIntoBufferAsync(size: Int): Future<ByteBuffer> =
            onReadAsync(bufferedPosition, size, readTimeoutMillis)

        fun reposition(oldPosition: Long, newPosition: Long) {
            if (newPosition == oldPosition) {
                return
            }
            val newBufferPosition = buffer.position() + (newPosition - oldPosition)
            if (newBufferPosition in 0..buffer.limit()) {
                buffer.position(newBufferPosition.toInt())
            } else {
                cancelPendingRead()
                buffer.limit(0)
                bufferedPosition = newPosition
            }
        }

        override fun close() {
            cancelPendingRead()
        }

        private fun cancelPendingRead() {
            synchronized(pendingReadLock) {
                pendingRead?.let {
                    abandonRead(it)
                    pendingRead = null
                }
            }
        }

        private fun abandonRead(future: Future<ByteBuffer>) {
            if (shouldCancelRead) {
                future.cancel(true)
                if (joinCancelledRead) {
                    try {
                        future.get(readTimeoutMillis, TimeUnit.MILLISECONDS)
                    } catch (e: Exception) {
                        // Ignored
                    }
                }
            }
        }
    }

    companion object {
        private const val BUFFER_SIZE = 1024 * 1024
        private const val FIRST_READ_SIZE = 128 * 1024
        private const val READ_TIMEOUT_MILLIS = 15_000L
    }
}
