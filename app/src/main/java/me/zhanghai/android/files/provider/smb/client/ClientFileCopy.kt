/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.smb.client

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileBasicInformation
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.smbj.ProgressListener
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import me.zhanghai.android.files.provider.common.copyTo
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.newOutputStream
import me.zhanghai.android.files.provider.smb.client.Client.Path
import me.zhanghai.android.files.util.enumSetOf
import me.zhanghai.android.files.util.logWarning

@Throws(ClientException::class)
internal fun copyOpenedFile(
    sourceSession: Session,
    sourceFile: File,
    targetSession: Session,
    targetShare: DiskShare,
    targetSharePath: Path.SharePath,
    copyAttributes: Boolean,
    intervalMillis: Long,
    listener: ((Long) -> Unit)?
) {
    val attributesToCopy = if (copyAttributes) {
        readFileAttributes(sourceFile)
    } else {
        enumSetOf(FileAttributes.FILE_ATTRIBUTE_NORMAL)
    }
    val targetFile = openTargetFile(targetShare, targetSharePath, attributesToCopy)
    targetFile.use {
        var successful = false
        try {
            if (sourceSession == targetSession) {
                serverCopy(sourceFile, targetFile, listener)
            } else {
                val sourceInputStream = FileByteChannel(sourceFile, false)
                    .newInputStream()
                val targetOutputStream = FileByteChannel(targetFile, false)
                    .newOutputStream()
                sourceInputStream.copyTo(targetOutputStream, intervalMillis, listener)
            }
            successful = true
        } catch (e: ClientException) {
            // The target exists now and may not get deleted, so starting over with a fresh
            // session (see withSession) would only fail on it; a plain failure it is.
            throw if (e.isSessionGone) ClientException(e.message, e) else e
        } finally {
            if (!successful) {
                deleteOnCloseSafe(targetFile)
            }
        }
    }
}

@Throws(ClientException::class)
private fun readFileAttributes(file: File): Set<FileAttributes> {
    val fileAttributes = try {
        file.getFileInformation(FileBasicInformation::class.java)
    } catch (e: SMBRuntimeException) {
        throw ClientException(e)
    }.fileAttributes
    return EnumWithValue.EnumUtils.toEnumSet(fileAttributes, FileAttributes::class.java)
}

@Throws(ClientException::class)
private fun openTargetFile(
    share: DiskShare,
    sharePath: Path.SharePath,
    attributes: Set<FileAttributes>
): File = try {
    share.openFile(
        sharePath.path,
        enumSetOf(
            AccessMask.FILE_WRITE_DATA,
            AccessMask.FILE_WRITE_ATTRIBUTES,
            AccessMask.FILE_WRITE_EA,
            AccessMask.DELETE
        ),
        attributes,
        SMB2ShareAccess.ALL,
        SMB2CreateDisposition.FILE_CREATE,
        enumSetOf(SMB2CreateOptions.FILE_OPEN_REPARSE_POINT)
    )
} catch (e: SMBRuntimeException) {
    throw ClientException(e)
}

/** Copies within one server with FSCTL_SRV_COPYCHUNK, so the data never leaves it. */
@Throws(ClientException::class)
private fun serverCopy(sourceFile: File, targetFile: File, listener: ((Long) -> Unit)?) {
    val length = try {
        sourceFile.getFileInformation(FileStandardInformation::class.java)
    } catch (e: SMBRuntimeException) {
        throw ClientException(e)
    }.endOfFile
    val progressListener = listener?.let {
        var lastCopiedSize = 0L
        ProgressListener { copiedSize, _ ->
            it(copiedSize - lastCopiedSize)
            lastCopiedSize = copiedSize
        }
    }
    try {
        sourceFile.serverCopy(0, targetFile, 0, length, progressListener)
    } catch (e: SMBRuntimeException) {
        throw ClientException(e)
    }
}

private fun deleteOnCloseSafe(file: File) {
    try {
        file.deleteOnClose()
    } catch (e: SMBRuntimeException) {
        e.logWarning("ClientFileCopy", "copyOpenedFile")
    }
}
