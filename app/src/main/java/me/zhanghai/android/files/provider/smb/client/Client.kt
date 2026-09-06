/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.smb.client

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileSettableInformation
import com.hierynomus.mssmb2.SMB2CompletionFilter
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.mssmb2.messages.SMB2ChangeNotifyResponse
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.Directory
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Future
import java8.nio.channels.SeekableByteChannel
import me.zhanghai.android.files.provider.common.CloseableIterator
import me.zhanghai.android.files.util.enumSetOf
import me.zhanghai.android.files.util.hasBits

/**
 * The connections of this provider, one pool per authority, created on demand with credentials
 * from [authenticator]. Owned by the file system provider; a test constructs its own with a fake.
 */
class Client(internal val authenticator: Authenticator) {
    internal val client = SMBClient()

    internal val sessions = mutableMapOf<Authority, Session>()

    internal val directoryFileInformationCache =
        Collections.synchronizedMap(WeakHashMap<Path, FileInformation>())

    @Throws(ClientException::class)
    fun openByteChannel(
        path: Path,
        desiredAccess: Set<AccessMask>,
        fileAttributes: Set<FileAttributes>,
        shareAccess: Set<SMB2ShareAccess>,
        createDisposition: SMB2CreateDisposition,
        createOptions: Set<SMB2CreateOptions>,
        isAppend: Boolean
    ): SeekableByteChannel {
        val sharePath = path.sharePath ?: throw ClientException("$path does not have a share path")
        val session = getSession(path.authority)
        val share = getDiskShare(session, sharePath.name)
        val file = try {
            share.openFile(
                sharePath.path,
                desiredAccess,
                fileAttributes,
                shareAccess,
                createDisposition,
                createOptions
            )
        } catch (e: SMBRuntimeException) {
            throw ClientException(e)
        }
        return FileByteChannel(file, isAppend)
    }

    @Throws(ClientException::class)
    fun openDirectoryIterator(path: Path): CloseableIterator<Path> {
        val session = getSession(path.authority)
        val sharePath = path.sharePath
        return if (sharePath == null) {
            openShareIterator(path, session)
        } else {
            openDirectoryEntryIterator(path, session, sharePath)
        }
    }

    // @see https://gitlab.com/samba-team/devel/samba/-/blob/master/source3/libsmb/cli_smb2_fnum.c
    // cli_smb2_mkdir_send
    @Throws(ClientException::class)
    fun createDirectory(path: Path, fileAttributes: Set<FileAttributes>? = null) {
        val sharePath = path.sharePath ?: throw ClientException("$path does not have a share path")
        val session = getSession(path.authority)
        val share = getDiskShare(session, sharePath.name)
        val directory = try {
            share.openDirectory(
                sharePath.path,
                enumSetOf(AccessMask.FILE_READ_ATTRIBUTES, AccessMask.FILE_READ_EA),
                enumSetOf(FileAttributes.FILE_ATTRIBUTE_DIRECTORY)
                    .apply { fileAttributes?.let { addAll(it) } },
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_CREATE,
                enumSetOf(SMB2CreateOptions.FILE_OPEN_REPARSE_POINT)
            )
        } catch (e: SMBRuntimeException) {
            throw ClientException(e)
        }
        try {
            directory.close()
        } catch (e: SMBRuntimeException) {
            throw ClientException(e)
        }
    }

    // @see https://gitlab.com/samba-team/devel/samba/-/blob/master/source3/libsmb/clisymlink.c
    //      cli_symlink_send
    @Throws(ClientException::class)
    fun createSymbolicLink(
        path: Path,
        reparseData: SymbolicLinkReparseData,
        fileAttributes: Set<FileAttributes>? = null
    ) {
        val sharePath = path.sharePath ?: throw ClientException("$path does not have a share path")
        val session = getSession(path.authority)
        val share = getDiskShare(session, sharePath.name)
        val diskEntry = try {
            share.open(
                sharePath.path,
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
                            e.printStackTrace()
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
    fun createLink(path: Path, link: Path, openReparsePoint: Boolean) {
        val sharePath = path.sharePath ?: throw ClientException("$path does not have a share path")
        val linkSharePath = link.sharePath
            ?: throw ClientException("$link does not have a share path")
        if (link.authority != path.authority || linkSharePath.name != sharePath.name) {
            throw ClientException(
                SMBApiException(
                    NtStatus.STATUS_NOT_SAME_DEVICE.value,
                    SMB2MessageCommandCode.SMB2_SET_INFO,
                    null
                )
            )
        }
        val session = getSession(path.authority)
        val share = getDiskShare(session, sharePath.name)
        val diskEntry = try {
            share.open(
                sharePath.path,
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
            diskEntry.use { it.createHardlink(linkSharePath.path, false) }
        } catch (e: SMBRuntimeException) {
            throw ClientException(e)
        }
    }

    @Throws(ClientException::class)
    fun delete(path: Path) {
        val sharePath = path.sharePath ?: throw ClientException("$path does not have a share path")
        val session = getSession(path.authority)
        val share = getDiskShare(session, sharePath.name)
        val diskEntry = try {
            share.open(
                sharePath.path,
                enumSetOf(AccessMask.DELETE),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                enumSetOf(
                    SMB2CreateOptions.FILE_DELETE_ON_CLOSE,
                    SMB2CreateOptions.FILE_OPEN_REPARSE_POINT
                )
            )
        } catch (e: SMBRuntimeException) {
            if (e is SMBApiException && e.status == NtStatus.STATUS_DELETE_PENDING) {
                return
            }
            throw ClientException(e)
        }
        try {
            diskEntry.close()
        } catch (e: SMBRuntimeException) {
            throw ClientException(e)
        }
        directoryFileInformationCache -= path
    }

    // @see https://gitlab.com/samba-team/devel/samba/-/blob/master/source3/libsmb/clisymlink.c
    //      cli_readlink_send
    @Throws(ClientException::class)
    fun readSymbolicLink(path: Path): SymbolicLinkReparseData {
        val sharePath = path.sharePath ?: throw ClientException("$path does not have a share path")
        val session = getSession(path.authority)
        val share = getDiskShare(session, sharePath.name)
        val diskEntry = try {
            share.open(
                sharePath.path,
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

    // @see https://docs.microsoft.com/en-us/openspecs/windows_protocols/ms-smb2/cd0162e4-7650-4293-8a2a-d696923203ef
    @Throws(ClientException::class)
    fun copyFile(
        source: Path,
        target: Path,
        copyAttributes: Boolean,
        openReparsePoint: Boolean,
        intervalMillis: Long,
        listener: ((Long) -> Unit)?
    ) {
        val sourceSharePath = source.sharePath
            ?: throw ClientException("$source does not have a share path")
        val targetSharePath = target.sharePath
            ?: throw ClientException("$target does not have a share path")
        val sourceSession = getSession(source.authority)
        val sourceShare = getDiskShare(sourceSession, sourceSharePath.name)
        val targetSession = getSession(target.authority)
        val targetShare = getDiskShare(targetSession, targetSharePath.name)
        val sourceFile = try {
            sourceShare.openFile(
                sourceSharePath.path,
                enumSetOf(
                    AccessMask.FILE_READ_DATA,
                    AccessMask.FILE_READ_ATTRIBUTES,
                    AccessMask.FILE_READ_EA
                ),
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
            sourceFile.use {
                copyOpenedFile(
                    sourceSession,
                    sourceFile,
                    targetSession,
                    targetShare,
                    targetSharePath,
                    copyAttributes,
                    intervalMillis,
                    listener
                )
            }
        } catch (e: SMBRuntimeException) {
            throw ClientException(e)
        }
    }

    // @see https://gitlab.com/samba-team/devel/samba/-/blob/master/source3/libsmb/cli_smb2_fnum.c
    //      cli_smb2_rename
    @Throws(ClientException::class)
    fun rename(path: Path, newPath: Path) {
        val sharePath = path.sharePath ?: throw ClientException("$path does not have a share path")
        val newSharePath = newPath.sharePath
            ?: throw ClientException("$newPath does not have a share path")
        if (newPath.authority != path.authority || newSharePath.name != sharePath.name) {
            throw ClientException(
                SMBApiException(
                    NtStatus.STATUS_NOT_SAME_DEVICE.value,
                    SMB2MessageCommandCode.SMB2_SET_INFO,
                    null
                )
            )
        }
        val session = getSession(path.authority)
        val share = getDiskShare(session, sharePath.name)
        val diskEntry = try {
            share.open(
                sharePath.path,
                enumSetOf(AccessMask.DELETE),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                enumSetOf(SMB2CreateOptions.FILE_OPEN_REPARSE_POINT)
            )
        } catch (e: SMBRuntimeException) {
            throw ClientException(e)
        }
        try {
            diskEntry.use { it.rename(newSharePath.path, true) }
        } catch (e: SMBRuntimeException) {
            throw ClientException(e)
        }
        directoryFileInformationCache -= path
        directoryFileInformationCache -= newPath
    }

    @Throws(ClientException::class)
    fun getPathInformation(path: Path, openReparsePoint: Boolean): PathInformation {
        val sharePath = path.sharePath ?: throw ClientException("$path does not have a share path")
        val session = getSession(path.authority)
        if (sharePath.path.isEmpty()) {
            return getShareInformation(session, sharePath.name)
        } else {
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
            return getFileInformation(session, sharePath, openReparsePoint)
        }
    }

    @Throws(ClientException::class)
    fun setFileInformation(
        path: Path,
        openReparsePoint: Boolean,
        fileInformation: FileSettableInformation
    ) {
        val sharePath = path.sharePath ?: throw ClientException("$path does not have a share path")
        val session = getSession(path.authority)
        val share = getDiskShare(session, sharePath.name)
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

    @Throws(ClientException::class)
    fun checkAccess(path: Path, desiredAccess: Set<AccessMask>, openReparsePoint: Boolean) {
        val sharePath = path.sharePath ?: throw ClientException("$path does not have a share path")
        val session = getSession(path.authority)
        val share = getDiskShare(session, sharePath.name)
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

    // @see https://docs.microsoft.com/en-us/openspecs/windows_protocols/ms-smb2/05869c32-39f0-4726-afc9-671b76ae5ca7
    @Throws(ClientException::class)
    fun openDirectoryForChangeNotification(path: Path): Directory {
        val sharePath = path.sharePath ?: throw ClientException("$path does not have a share path")
        val session = getSession(path.authority)
        val share = getDiskShare(session, sharePath.name)
        return try {
            share.openDirectory(
                sharePath.path,
                enumSetOf(AccessMask.FILE_LIST_DIRECTORY),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            )
        } catch (e: SMBRuntimeException) {
            throw ClientException(e)
        }
    }

    @Throws(ClientException::class)
    fun requestDirectoryChangeNotification(
        directory: Directory,
        completionFilter: Set<SMB2CompletionFilter>
    ): Future<SMB2ChangeNotifyResponse> = try {
        directory.watchAsync(completionFilter, false)
    } catch (e: SMBRuntimeException) {
        throw ClientException(e)
    }

    interface Path {
        val authority: Authority
        val sharePath: SharePath?
        fun resolve(other: String): Path

        data class SharePath(val name: String, val path: String)
    }
}
