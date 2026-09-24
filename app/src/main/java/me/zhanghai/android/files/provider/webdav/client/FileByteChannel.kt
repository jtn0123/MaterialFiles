/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.webdav.client

import at.bitfire.dav4jvm.DavResource
import at.bitfire.dav4jvm.exception.DavException
import at.bitfire.dav4jvm.exception.HttpException
import at.bitfire.dav4jvm.property.webdav.GetContentLength
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import me.zhanghai.android.files.provider.common.AbstractFileByteChannel
import me.zhanghai.android.files.provider.common.EMPTY
import me.zhanghai.android.files.provider.common.readFully
import me.zhanghai.android.files.provider.webdav.toFileSystemException
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * A channel on [resource], which is [file] to the user.
 *
 * What the server refuses arrives as a [DavException], which is not an [IOException]; the channel
 * reports it as the [java8.nio.file.FileSystemException] for [file] instead, as the channel of
 * any other provider would.
 */
// https://blog.sphere.chronosempire.org.uk/2012/11/21/webdav-and-the-http-patch-nightmare
class FileByteChannel(
    private val client: Client,
    private val resource: DavResource,
    private val patchSupport: PatchSupport,
    isAppend: Boolean,
    private val file: String
) : AbstractFileByteChannel(isAppend) {
    private var nextSequentialWritePosition = 0L
    private var sequentialWriteOutputStream: OutputStream? = null

    @Throws(IOException::class)
    override fun onRead(position: Long, size: Int): ByteBuffer {
        val inputStream = try {
            resource.getRangeCompat("*/*", position, size, null)
        } catch (e: HttpException) {
            if (e.code == HTTP_RANGE_NOT_SATISFIABLE) {
                // We were reading at/past end of file
                return ByteBuffer::class.EMPTY
            }
            throw e.toFileSystemException(file)
        } catch (e: DavException) {
            throw e.toFileSystemException(file)
        }
        val destination = ByteBuffer.allocate(size)
        val limit = inputStream.use {
            it.readFully(destination.array(), destination.arrayOffset(), size)
        }
        destination.limit(limit)
        return destination
    }

    @Throws(IOException::class)
    override fun onWrite(position: Long, source: ByteBuffer) {
        mapDavException {
            when (patchSupport) {
                PatchSupport.APACHE ->
                    resource.putRangeCompat(source, position) {}

                PatchSupport.SABRE ->
                    resource.patchCompat(source, position) {}

                PatchSupport.NONE -> writeSequentially(position, source)
            }
        }
    }

    @Throws(DavException::class, IOException::class)
    private fun writeSequentially(position: Long, source: ByteBuffer) {
        if (position != nextSequentialWritePosition) {
            throw IOException("Unsupported non-sequential write")
        }
        val outputStream = sequentialWriteOutputStream
            ?: resource.putCompat().also { sequentialWriteOutputStream = it }
        val remaining = source.remaining()
        // I don't think we are using native or read-only ByteBuffer, so just call array()
        // here.
        outputStream.write(
            source.array(),
            source.arrayOffset() + source.position(),
            remaining
        )
        // The caller counts what was written by how far the buffer moved.
        source.position(source.limit())
        nextSequentialWritePosition += remaining
    }

    @Throws(IOException::class)
    override fun onTruncate(size: Long) {
        if (size == 0L) {
            mapDavException { resource.put(byteArrayOf().toRequestBody()) {} }
        } else {
            throw IOException("Unsupported truncate to non-zero size")
        }
    }

    @Throws(IOException::class)
    override fun onSize(): Long {
        val getContentLength = mapDavException {
            client.findProperties(resource, GetContentLength.NAME)[GetContentLength::class.java]
        } ?: throw IOException("Missing GetContentLength")
        return getContentLength.contentLength ?: throw IOException("Invalid GetContentLength")
    }

    @Throws(IOException::class)
    override fun onClose() {
        mapDavException { sequentialWriteOutputStream?.close() }
    }

    private inline fun <T> mapDavException(block: () -> T): T = try {
        block()
    } catch (e: DavException) {
        throw e.toFileSystemException(file)
    }

    companion object {
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
    }
}
