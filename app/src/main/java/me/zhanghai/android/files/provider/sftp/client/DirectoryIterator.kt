/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.sftp.client

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit
import java8.nio.file.DirectoryIteratorException
import me.zhanghai.android.files.provider.common.CloseableIterator
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.PacketType
import net.schmizz.sshj.sftp.RemoteResource
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPEngine
import net.schmizz.sshj.sftp.SFTPException

/** An entry of a directory listing, with the attributes the server sent along (from lstat()). */
internal class DirectoryEntry(val name: String, val attributes: FileAttributes)

/** An open directory that is read one batch of entries at a time. */
internal interface DirectoryBatchReader : Closeable {
    /** The next batch of entries without `.` and `..`, or `null` once there are no more. */
    @Throws(IOException::class)
    fun readBatch(): List<DirectoryEntry>?
}

/**
 * A directory opened on the server and read with one READDIR request per batch. sshj's own
 * `RemoteDirectory.scan()` keeps reading until the end, so a large folder would show nothing until
 * dozens of round trips are done.
 */
internal class RemoteDirectoryReader private constructor(
    engine: SFTPEngine,
    path: String,
    handle: ByteArray
) : RemoteResource(engine, path, handle),
    DirectoryBatchReader {
    private var isAtEnd = false

    @Throws(IOException::class)
    override fun readBatch(): List<DirectoryEntry>? {
        if (isAtEnd) {
            return null
        }
        val response = requester.request(newRequest(PacketType.READDIR))
            .retrieve(requester.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        return when (response.type) {
            PacketType.NAME -> {
                val charset = requester.subsystem.remoteCharset
                val count = response.readUInt32AsInt()
                List(count) {
                    val name = response.readString(charset)
                    // The long name, as `ls -l` would print it.
                    response.readString()
                    DirectoryEntry(name, response.readFileAttributes())
                }.filter { it.name != "." && it.name != ".." }
            }

            PacketType.STATUS -> {
                response.ensureStatusIs(Response.StatusCode.EOF)
                isAtEnd = true
                null
            }

            else -> throw SFTPException("Unexpected packet: ${response.type}")
        }
    }

    companion object {
        @Throws(IOException::class)
        fun open(engine: SFTPEngine, path: String): RemoteDirectoryReader {
            val request = engine.newRequest(PacketType.OPENDIR)
                .putString(path, engine.subsystem.remoteCharset)
            val handle = engine.request(request)
                .retrieve(engine.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .ensurePacketTypeIs(PacketType.HANDLE)
                .readBytes()
            return RemoteDirectoryReader(engine, path, handle)
        }
    }
}

/**
 * The entries of [directory] as paths below [path], fetched a batch at a time as they are asked
 * for, starting with [firstBatch] (`null` if the directory was already read to the end), and
 * handed to [onEntry] with their attributes.
 *
 * A batch that fails is thrown as the [DirectoryIteratorException] that directory streams use for
 * it, around the provider's exception for [path].
 */
internal class DirectoryEntryIterator(
    private val directory: DirectoryBatchReader,
    firstBatch: List<DirectoryEntry>?,
    private val path: Client.Path,
    private val onEntry: (Client.Path, FileAttributes) -> Unit
) : CloseableIterator<Client.Path> {
    private var batch = firstBatch.orEmpty().iterator()

    private var isAtEnd = firstBatch == null

    private var isClosed = false

    override fun hasNext(): Boolean {
        while (!batch.hasNext()) {
            // A closed directory has no handle left to read the rest with.
            if (isAtEnd || isClosed) {
                return false
            }
            val nextBatch = try {
                directory.readBatch()
            } catch (e: IOException) {
                throw DirectoryIteratorException(
                    ClientException(e).toFileSystemException(path.toString())
                )
            }
            if (nextBatch == null) {
                isAtEnd = true
                return false
            }
            batch = nextBatch.iterator()
        }
        return true
    }

    override fun next(): Client.Path {
        if (!hasNext()) {
            throw NoSuchElementException()
        }
        val entry = batch.next()
        return path.resolve(entry.name).also { onEntry(it, entry.attributes) }
    }

    @Throws(IOException::class)
    override fun close() {
        if (isClosed) {
            return
        }
        isClosed = true
        directory.close()
    }
}

/**
 * Opens [path] with [open] and reads its first batch, so that a directory that cannot be listed
 * fails here rather than on the first `hasNext()`.
 */
@Throws(ClientException::class)
internal fun openDirectoryEntryIterator(
    path: Client.Path,
    open: () -> DirectoryBatchReader,
    onEntry: (Client.Path, FileAttributes) -> Unit
): DirectoryEntryIterator {
    val directory = try {
        open()
    } catch (e: IOException) {
        throw ClientException(e)
    }
    val firstBatch = try {
        directory.readBatch()
    } catch (e: IOException) {
        val exception = ClientException(e)
        try {
            directory.close()
        } catch (closeException: IOException) {
            exception.addSuppressed(closeException)
        }
        throw exception
    }
    return DirectoryEntryIterator(directory, firstBatch, path, onEntry)
}
