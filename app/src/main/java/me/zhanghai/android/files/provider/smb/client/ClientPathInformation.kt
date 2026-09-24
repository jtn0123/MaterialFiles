/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.smb.client

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileSettableInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.PipeShare
import com.hierynomus.smbj.share.PrinterShare
import me.zhanghai.android.files.provider.smb.client.Client.Path
import me.zhanghai.android.files.util.closeSafe
import me.zhanghai.android.files.util.enumSetOf
import me.zhanghai.android.files.util.hasBits
import me.zhanghai.android.files.util.logWarning

@Throws(ClientException::class)
internal fun Client.getFileInformation(
    session: Session,
    sharePath: Path.SharePath,
    openReparsePoint: Boolean
): FileInformation {
    val share = getDiskShare(session, sharePath.name)
    val diskEntry = try {
        share.open(
            sharePath.path,
            enumSetOf(AccessMask.FILE_READ_ATTRIBUTES, AccessMask.FILE_READ_EA),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            if (openReparsePoint) {
                enumSetOf(SMB2CreateOptions.FILE_OPEN_REPARSE_POINT)
            } else {
                null
            }
        )
    } catch (e: SMBRuntimeException) {
        throw ClientException(e)
    }
    val fileAllInformation = try {
        diskEntry.use { it.fileInformation }
    } catch (e: SMBRuntimeException) {
        throw ClientException(e)
    }
    return fileAllInformation.toFileInformation()
}

@Throws(ClientException::class)
internal fun Client.getShareInformation(session: Session, shareName: String): ShareInformation =
    when (val share = getShare(session, shareName)) {
        is DiskShare -> {
            val shareInfo = try {
                share.shareInformation
            } catch (e: SMBRuntimeException) {
                e.logWarning("ClientPathInformation", "getShareInformation")
                null
            }
            ShareInformation(ShareType.DISK, shareInfo)
            // Don't close the disk share, because it might still be in use, or might become
            // in use shortly. All shares are automatically closed when the session is
            // closed anyway.
        }

        is PipeShare -> ShareInformation(ShareType.PIPE, null).also { share.closeSafe() }

        is PrinterShare -> ShareInformation(ShareType.PRINTER, null)
            .also { share.closeSafe() }

        else -> throw AssertionError(share)
    }

@Throws(ClientException::class)
fun Client.getPathInformation(path: Path, openReparsePoint: Boolean): PathInformation {
    val sharePath = path.sharePath ?: throw ClientException("$path does not have a share path")
    if (sharePath.path.isNotEmpty()) {
        synchronized(directoryFileInformationCache) {
            directoryFileInformationCache[path]?.let {
                if (openReparsePoint || !it.fileAttributes.hasBits(
                        FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.value
                    )
                ) {
                    return it.also { directoryFileInformationCache -= path }
                }
            }
        }
    }
    return withSession(path.authority) { session ->
        if (sharePath.path.isEmpty()) {
            getShareInformation(session, sharePath.name)
        } else {
            getFileInformation(session, sharePath, openReparsePoint)
        }
    }
}

@Throws(ClientException::class)
fun Client.setFileInformation(
    path: Path,
    openReparsePoint: Boolean,
    fileInformation: FileSettableInformation
) {
    withDiskShare(path) { share, sharePath ->
        val diskEntry = try {
            share.open(
                sharePath.path,
                enumSetOf(AccessMask.FILE_WRITE_ATTRIBUTES, AccessMask.FILE_WRITE_EA),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                if (openReparsePoint) {
                    enumSetOf(SMB2CreateOptions.FILE_OPEN_REPARSE_POINT)
                } else {
                    null
                }
            )
        } catch (e: SMBRuntimeException) {
            throw ClientException(e)
        }
        try {
            diskEntry.use { it.setFileInformation(fileInformation) }
        } catch (e: SMBRuntimeException) {
            throw ClientException(e)
        }
        directoryFileInformationCache -= path
    }
}

@Throws(ClientException::class)
fun Client.checkAccess(path: Path, desiredAccess: Set<AccessMask>, openReparsePoint: Boolean) {
    withDiskShare(path) { share, sharePath ->
        val diskEntry = try {
            share.open(
                sharePath.path,
                desiredAccess,
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                if (openReparsePoint) {
                    enumSetOf(SMB2CreateOptions.FILE_OPEN_REPARSE_POINT)
                } else {
                    null
                }
            )
        } catch (e: SMBRuntimeException) {
            throw ClientException(e)
        }
        try {
            diskEntry.close()
        } catch (e: SMBRuntimeException) {
            throw ClientException(e)
        }
    }
}
