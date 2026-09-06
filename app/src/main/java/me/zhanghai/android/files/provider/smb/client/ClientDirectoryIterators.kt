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
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import java.io.Closeable
import java.io.IOException
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
    val netShareInfos = try {
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
        override fun close() {}
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
    val directoryIterator = directory.iterator(FileIdFullDirectoryInformation::class.java)
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
    return object :
        CloseableIterator<Path>,
        Iterator<Path> by directoryIterator,
        Closeable by directory {}
}
