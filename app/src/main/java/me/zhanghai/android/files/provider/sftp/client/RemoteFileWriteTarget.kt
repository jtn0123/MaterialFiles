/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.sftp.client

import java.io.IOException
import java.io.InterruptedIOException
import java.nio.channels.AsynchronousCloseException
import java.util.concurrent.TimeUnit
import me.zhanghai.android.files.util.findCauseByClass
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.RemoteFileAccessor
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPException

/** Sends [PipelinedOutputStream] writes to an sshj [RemoteFile] as SSH_FXP_WRITE requests. */
internal class RemoteFileWriteTarget(private val file: RemoteFile) : PipelinedWriteTarget {
    private val requester = RemoteFileAccessor.getRequester(file)

    override val maxWriteSize: Int =
        requester.subsystem.remoteMaxPacketSize - file.outgoingPacketOverhead

    @Throws(IOException::class)
    override fun writeAsync(
        fileOffset: Long,
        data: ByteArray,
        offset: Int,
        length: Int
    ): PendingWrite {
        val promise = try {
            RemoteFileAccessor.asyncWrite(file, fileOffset, data, offset, length)
        } catch (e: IOException) {
            throw e.toStreamException()
        }
        return PendingWrite {
            try {
                promise.retrieve(requester.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                    .ensureStatusPacketIsOK()
            } catch (e: IOException) {
                throw e.toStreamException()
            }
        }
    }

    @Throws(IOException::class)
    override fun close() {
        try {
            file.close()
        } catch (e: SFTPException) {
            // NO_SUCH_FILE is returned when canceling an in-progress copy to SFTP server.
            if (e.statusCode != Response.StatusCode.NO_SUCH_FILE) {
                throw e.toStreamException()
            }
        } catch (e: IOException) {
            throw e.toStreamException()
        }
    }

    private fun IOException.toStreamException(): IOException = when {
        this is SFTPException && statusCode == Response.StatusCode.INVALID_HANDLE ->
            AsynchronousCloseException().apply { initCause(this@toStreamException) }

        findCauseByClass<InterruptedException>() != null ->
            InterruptedIOException().apply { initCause(this@toStreamException) }

        else -> this
    }
}
