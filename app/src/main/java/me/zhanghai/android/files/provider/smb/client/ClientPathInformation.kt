/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.smb.client

import com.hierynomus.msdtyp.AccessMask
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
                e.printStackTrace()
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
