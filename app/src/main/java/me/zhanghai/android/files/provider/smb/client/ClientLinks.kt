/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.smb.client

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.share.DiskShare
import me.zhanghai.android.files.provider.smb.client.Client.Path
import me.zhanghai.android.files.util.enumSetOf
import me.zhanghai.android.files.util.logWarning

// @see https://gitlab.com/samba-team/devel/samba/-/blob/master/source3/libsmb/clisymlink.c
//      cli_symlink_send
@Throws(ClientException::class)
internal fun DiskShare.createSymbolicLink(
    sharePath: String,
    reparseData: SymbolicLinkReparseData,
    fileAttributes: Set<FileAttributes>?,
    path: Path
) {
    val diskEntry = try {
        open(
            sharePath,
            enumSetOf(
                AccessMask.FILE_READ_ATTRIBUTES,
                AccessMask.FILE_WRITE_ATTRIBUTES,
                AccessMask.FILE_READ_EA,
                AccessMask.FILE_WRITE_EA,
                AccessMask.DELETE,
                AccessMask.SYNCHRONIZE
            ),
            enumSetOf<FileAttributes>().apply {
                fileAttributes?.let { addAll(it) }
                this -= FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT
                if (isEmpty()) {
                    this += FileAttributes.FILE_ATTRIBUTE_NORMAL
                }
            },
            null,
            SMB2CreateDisposition.FILE_CREATE,
            enumSetOf(
                SMB2CreateOptions.FILE_NON_DIRECTORY_FILE,
                SMB2CreateOptions.FILE_OPEN_REPARSE_POINT
            )
        )
    } catch (e: SMBRuntimeException) {
        throw ClientException(e)
    }
    try {
        diskEntry.use {
            var successful = false
            try {
                it.setSymbolicLinkReparseData(reparseData)
                successful = true
            } finally {
                if (!successful) {
                    try {
                        it.deleteOnClose()
                    } catch (e: SMBRuntimeException) {
                        e.logWarning("SmbClient", "createSymbolicLink($path)")
                    }
                }
            }
        }
    } catch (e: SMBRuntimeException) {
        throw ClientException(e)
    }
}

// @see https://gitlab.com/samba-team/devel/samba/-/blob/master/source3/libsmb/clifile.c
//      cli_smb2_hardlink_send
@Throws(ClientException::class)
internal fun DiskShare.createHardLink(
    sharePath: String,
    linkSharePath: String,
    openReparsePoint: Boolean
) {
    val diskEntry = try {
        open(
            sharePath,
            enumSetOf(AccessMask.FILE_WRITE_ATTRIBUTES, AccessMask.FILE_WRITE_EA),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            // CreateHardLink doesn't work for directories.
            enumSetOf(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE).apply {
                if (openReparsePoint) {
                    this += SMB2CreateOptions.FILE_OPEN_REPARSE_POINT
                }
            }
        )
    } catch (e: SMBRuntimeException) {
        throw ClientException(e)
    }
    try {
        diskEntry.use { it.createHardlink(linkSharePath, false) }
    } catch (e: SMBRuntimeException) {
        throw ClientException(e)
    }
}

// @see https://gitlab.com/samba-team/devel/samba/-/blob/master/source3/libsmb/clisymlink.c
//      cli_readlink_send
@Throws(ClientException::class)
internal fun DiskShare.readSymbolicLink(sharePath: String): SymbolicLinkReparseData {
    val diskEntry = try {
        open(
            sharePath,
            enumSetOf(AccessMask.FILE_READ_ATTRIBUTES, AccessMask.FILE_READ_EA),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            enumSetOf(SMB2CreateOptions.FILE_OPEN_REPARSE_POINT)
        )
    } catch (e: SMBRuntimeException) {
        throw ClientException(e)
    }
    return try {
        diskEntry.use { it.getSymbolicLinkReparseData() }
    } catch (e: SMBRuntimeException) {
        throw ClientException(e)
    }
}
