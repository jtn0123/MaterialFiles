/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.smb.client

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.fileinformation.FileIdFullDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.session.Session
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.mssrvs.dto.NetShareInfo1
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import java.io.Closeable
import java.io.IOException
import java8.nio.file.DirectoryIteratorException
import me.zhanghai.android.files.provider.common.CloseableIterator
import me.zhanghai.android.files.provider.smb.client.Client.Path
import me.zhanghai.android.files.util.enumSetOf
import me.zhanghai.android.files.util.hasBits

@Throws(ClientException::class)
internal fun Client.openShareIterator(path: Path, session: Session): CloseableIterator<Path> {
    val transport = try {
        SMBTransportFactories.SRVSVC.getTransport(session)
    } catch (e: IOException) {
        throw ClientException(e)
    } catch (e: SMBRuntimeException) {
        throw ClientException(e)
    }
    val serverService = ServerService(transport)
    val netShareInfos: List<NetShareInfo1> = try {
        serverService.shares1
    } catch (e: IOException) {
        throw ClientException(e)
    } catch (e: SMBRuntimeException) {
        throw ClientException(e)
    }
    val sharePaths = netShareInfos.mapNotNull {
        if (!(
                it.type.hasBits(ShareTypes.STYPE_PRINTQ.value) ||
                    it.type.hasBits(ShareTypes.STYPE_DEVICE.value) ||
                    it.type.hasBits(ShareTypes.STYPE_IPC.value)
                )
        ) {
            path.resolve(it.netName)
        } else {
            null
        }
    }
    return object : CloseableIterator<Path>, Iterator<Path> by sharePaths.iterator() {
        override fun close() {
            // The shares were all fetched above, and the RPC transport has nothing to close.
        }
    }
}

@Throws(ClientException::class)
internal fun Client.openDirectoryEntryIterator(
    path: Path,
    session: Session,
    sharePath: Path.SharePath
): CloseableIterator<Path> {
    val share = getDiskShare(session, sharePath.name)
    val directory = try {
        share.openDirectory(
            sharePath.path,
            enumSetOf(
                AccessMask.FILE_LIST_DIRECTORY,
                AccessMask.FILE_READ_ATTRIBUTES,
                AccessMask.FILE_READ_EA
            ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null
        )
    } catch (e: SMBRuntimeException) {
        throw ClientException(e)
    }
    // SMBJ sends the first QUERY_DIRECTORY right here, so a failure here is a failure to list: it
    // has to reach withSession as a ClientException, and the handle must not be left open.
    val fileInformations = closeOnSmbFailure(directory) {
        directory.iterator(FileIdFullDirectoryInformation::class.java)
    }
    val directoryIterator = fileInformations
        .asSequence()
        .filter { fileInformation ->
            !fileInformation.fileName.let { it == "." || it == ".." }
        }
        .map { fileInformation ->
            path.resolve(fileInformation.fileName).also {
                directoryFileInformationCache[it] = fileInformation.toFileInformation()
            }
        }
        .iterator()
        .mapSmbFailures(path.toString())
    return object :
        CloseableIterator<Path>,
        Iterator<Path> by directoryIterator,
        Closeable by directory {}
}

/** Runs [block], closing [closeable] and throwing a [ClientException] if SMBJ fails in it. */
@Throws(ClientException::class)
internal inline fun <T> closeOnSmbFailure(closeable: AutoCloseable, block: () -> T): T = try {
    block()
} catch (e: SMBRuntimeException) {
    val exception = ClientException(e)
    try {
        closeable.close()
    } catch (closeException: Exception) {
        exception.addSuppressed(closeException)
    }
    throw exception
}

/**
 * Later batches of a listing are fetched from `hasNext()`, which can only throw unchecked. A
 * failure there is thrown as the [DirectoryIteratorException] that directory streams use for it,
 * around the same [java8.nio.file.FileSystemException] the provider would throw for [path], so
 * that it reads "Could not connect" or "Sign-in failed" rather than SMBJ's own message.
 */
internal fun <T> Iterator<T>.mapSmbFailures(path: String): Iterator<T> {
    val iterator = this
    return object : Iterator<T> {
        override fun hasNext(): Boolean = mapSmbFailure { iterator.hasNext() }

        override fun next(): T = mapSmbFailure { iterator.next() }

        private inline fun <R> mapSmbFailure(block: () -> R): R = try {
            block()
        } catch (e: SMBRuntimeException) {
            throw DirectoryIteratorException(ClientException(e).toFileSystemException(path))
        }
    }
}
