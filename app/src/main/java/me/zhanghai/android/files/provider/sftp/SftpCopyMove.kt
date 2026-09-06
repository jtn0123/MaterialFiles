/*
 * Copyright (c) 2021 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.sftp

import java.io.IOException
import java.time.Instant
import java8.nio.file.FileSystemException
import java8.nio.file.NoSuchFileException
import me.zhanghai.android.files.provider.common.AbstractCopyMove
import me.zhanghai.android.files.provider.common.CopyOptions
import me.zhanghai.android.files.provider.common.copyTo
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.newOutputStream
import me.zhanghai.android.files.provider.common.replacementSibling
import me.zhanghai.android.files.provider.sftp.client.Client
import me.zhanghai.android.files.provider.sftp.client.ClientException
import me.zhanghai.android.files.util.enumSetOf
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode

internal object SftpCopyMove : AbstractCopyMove<SftpPath, FileAttributes>() {
    override fun readAttributes(path: SftpPath, noFollowLinks: Boolean): FileAttributes {
        val attributes = try {
            if (noFollowLinks) client.lstat(path) else client.stat(path)
        } catch (e: ClientException) {
            throw e.toFileSystemException(path.toString())
        }
        if (!attributes.has(FileAttributes.Flag.MODE)) {
            throw FileSystemException(
                path.toString(),
                null,
                "Missing SSH_FILEXFER_ATTR_PERMISSIONS"
            )
        }
        return attributes
    }

    override fun readAttributesOrNull(path: SftpPath): FileAttributes? = try {
        client.lstat(path)
    } catch (e: ClientException) {
        val exception = e.toFileSystemException(path.toString())
        if (exception !is NoSuchFileException) {
            throw exception
        }
        null
    }

    override fun isSameFile(
        source: SftpPath,
        sourceAttributes: FileAttributes,
        target: SftpPath,
        targetAttributes: FileAttributes
    ): Boolean = source == target

    override fun getFileType(attributes: FileAttributes): FileType = when (attributes.type) {
        FileMode.Type.REGULAR -> FileType.REGULAR_FILE
        FileMode.Type.DIRECTORY -> FileType.DIRECTORY
        FileMode.Type.SYMLINK -> FileType.SYMBOLIC_LINK
        else -> FileType.OTHER
    }

    override fun getSize(attributes: FileAttributes): Long =
        if (attributes.has(FileAttributes.Flag.SIZE)) attributes.size else 0

    override fun copyRegularFile(
        source: SftpPath,
        sourceAttributes: FileAttributes,
        target: SftpPath,
        copyOptions: CopyOptions
    ) {
        val sourceInputStream = try {
            client.openByteChannel(source, enumSetOf(OpenMode.READ), FileAttributes.EMPTY)
        } catch (e: ClientException) {
            throw e.toFileSystemException(source.toString())
        }.newInputStream()
        try {
            val targetFlags =
                enumSetOf(OpenMode.WRITE, OpenMode.TRUNC, OpenMode.CREAT, OpenMode.EXCL)
            val targetOutputStream = try {
                client.openByteChannel(target, targetFlags, sourceAttributes.toModeAttributes())
            } catch (e: ClientException) {
                throw e.toFileSystemException(target.toString())
            }.newOutputStream()
            try {
                sourceInputStream.copyTo(
                    targetOutputStream,
                    copyOptions.progressIntervalMillis,
                    copyOptions.progressListener
                )
            } finally {
                try {
                    targetOutputStream.close()
                } catch (e: IOException) {
                    throw ClientException(e).toFileSystemException(target.toString())
                }
            }
        } finally {
            try {
                sourceInputStream.close()
            } catch (e: IOException) {
                throw ClientException(e).toFileSystemException(source.toString())
            }
        }
    }

    override fun createDirectory(
        target: SftpPath,
        sourceAttributes: FileAttributes,
        copyOptions: CopyOptions
    ) {
        try {
            client.mkdir(target, sourceAttributes.toModeAttributes())
        } catch (e: ClientException) {
            throw e.toFileSystemException(target.toString())
        }
    }

    override fun copySymbolicLink(
        source: SftpPath,
        sourceAttributes: FileAttributes,
        target: SftpPath,
        copyOptions: CopyOptions
    ) {
        val sourceTarget = try {
            client.readlink(source)
        } catch (e: ClientException) {
            throw e.toFileSystemException(source.toString())
        }
        try {
            client.symlink(target, sourceTarget)
        } catch (e: ClientException) {
            throw e.toFileSystemException(target.toString())
        }
    }

    override fun delete(path: SftpPath) {
        try {
            client.remove(path)
        } catch (e: ClientException) {
            val exception = e.toFileSystemException(path.toString())
            if (exception !is NoSuchFileException) {
                throw exception
            }
        }
    }

    override fun replacementSibling(target: SftpPath): SftpPath =
        target.replacementSibling() as SftpPath

    // SFTP rename does not overwrite, so a target being replaced goes first.
    override fun rename(source: SftpPath, target: SftpPath, replaceExisting: Boolean) {
        if (replaceExisting) {
            delete(target)
        }
        try {
            client.rename(source, target)
        } catch (e: ClientException) {
            throw e.toFileSystemException(source.toString(), target.toString())
        }
    }

    override fun copyAttributes(
        source: SftpPath,
        sourceAttributes: FileAttributes,
        target: SftpPath,
        copyOptions: CopyOptions
    ) {
        if (sourceAttributes.type == FileMode.Type.SYMLINK) {
            return
        }
        val attributes = FileAttributes.Builder()
            .apply {
                if (copyOptions.copyAttributes &&
                    sourceAttributes.has(FileAttributes.Flag.UIDGID)
                ) {
                    withUIDGID(sourceAttributes.uid, sourceAttributes.gid)
                }
                if (sourceAttributes.has(FileAttributes.Flag.MODE)) {
                    withPermissions(sourceAttributes.mode.mask)
                }
                if (sourceAttributes.has(FileAttributes.Flag.ACMODTIME)) {
                    withAtimeMtime(
                        if (copyOptions.copyAttributes) {
                            sourceAttributes.atime
                        } else {
                            // We cannot leave atime unchanged in SFTP, but since we've just
                            // written the file, its atime is simply now.
                            Instant.now().epochSecond
                        },
                        sourceAttributes.mtime
                    )
                }
            }
            .build()
        try {
            client.setstat(target, attributes)
        } catch (e: ClientException) {
            e.printStackTrace()
        }
    }

    private fun FileAttributes.toModeAttributes(): FileAttributes = FileAttributes.Builder()
        .apply {
            if (has(FileAttributes.Flag.MODE)) {
                withPermissions(mode.mask)
            }
        }
        .build()
}
