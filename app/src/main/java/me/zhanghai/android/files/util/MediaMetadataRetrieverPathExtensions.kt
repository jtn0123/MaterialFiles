/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java8.nio.channels.SeekableByteChannel
import java8.nio.file.Path
import me.zhanghai.android.files.provider.common.newByteChannel
import me.zhanghai.android.files.provider.document.isDocumentPath
import me.zhanghai.android.files.provider.document.openDocumentParcelFileDescriptor
import me.zhanghai.android.files.provider.ftp.isFtpPath
import me.zhanghai.android.files.provider.linux.isLinuxPath

val Path.isMediaMetadataRetrieverCompatible: Boolean
    get() = !isFtpPath

fun MediaMetadataRetriever.setDataSource(path: Path) {
    setDataSource(path) {}
}

/**
 * @param onChannelOpened receives the channel the retriever reads a non-local file through, before
 * any blocking read of it. Closing that channel from another thread makes the retriever fail at
 * its next read, which is the only way to abandon a retriever that is reading from a server: its
 * reads happen in native code that thread interruption does not reach.
 */
fun MediaMetadataRetriever.setDataSource(path: Path, onChannelOpened: (Closeable) -> Unit) {
    when {
        path.isLinuxPath -> setDataSource(path.toFile().path)

        path.isDocumentPath ->
            path.openDocumentParcelFileDescriptor("r")
                .use { pfd -> setDataSource(pfd.fileDescriptor) }

        else -> {
            val channel = try {
                path.newByteChannel()
            } catch (e: IOException) {
                throw IllegalArgumentException(e)
            }
            onChannelOpened(channel)
            setDataSource(PathMediaDataSource(channel))
        }
    }
}

private class PathMediaDataSource(private val channel: SeekableByteChannel) : MediaDataSource() {
    @Throws(IOException::class)
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        channel.position(position)
        return channel.read(ByteBuffer.wrap(buffer, offset, size))
    }

    @Throws(IOException::class)
    override fun getSize(): Long = channel.size()

    @Throws(IOException::class)
    override fun close() {
        channel.close()
    }
}
