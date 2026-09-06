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
        val sourceAttributes = try {
            sourceFile.getFileInformation(FileBasicInformation::class.java)
        } catch (e: SMBRuntimeException) {
            throw ClientException(e)
        }.fileAttributes
        EnumWithValue.EnumUtils.toEnumSet(sourceAttributes, FileAttributes::class.java)
    } else {
        enumSetOf(FileAttributes.FILE_ATTRIBUTE_NORMAL)
    }
    val targetFile = try {
        targetShare.openFile(
            targetSharePath.path,
            enumSetOf(
                AccessMask.FILE_WRITE_DATA,
                AccessMask.FILE_WRITE_ATTRIBUTES,
                AccessMask.FILE_WRITE_EA,
                AccessMask.DELETE
            ),
            attributesToCopy,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_CREATE,
            enumSetOf(SMB2CreateOptions.FILE_OPEN_REPARSE_POINT)
        )
    } catch (e: SMBRuntimeException) {
        throw ClientException(e)
    }
    targetFile.use {
        var successful = false
        try {
            if (sourceSession == targetSession) {
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
            } else {
                val sourceInputStream = FileByteChannel(sourceFile, false)
                    .newInputStream()
                val targetOutputStream = FileByteChannel(targetFile, false)
                    .newOutputStream()
                sourceInputStream.copyTo(targetOutputStream, intervalMillis, listener)
            }
            successful = true
        } finally {
            if (!successful) {
                try {
                    targetFile.deleteOnClose()
                } catch (e: SMBRuntimeException) {
                    e.printStackTrace()
                }
            }
        }
    }
}
