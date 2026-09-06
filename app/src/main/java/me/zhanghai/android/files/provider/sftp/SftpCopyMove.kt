/*
 * Copyright (c) 2021 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.sftp

import java.io.IOException
import java.time.Instant
import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.FileSystemException
import java8.nio.file.NoSuchFileException
import java8.nio.file.StandardCopyOption
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

internal object SftpCopyMove {
    @Throws(IOException::class)
    fun copy(source: SftpPath, target: SftpPath, copyOptions: CopyOptions) {
        if (copyOptions.atomicMove) {
            throw UnsupportedOperationException(StandardCopyOption.ATOMIC_MOVE.toString())
        }
        val sourceAttributes = try {
            if (copyOptions.noFollowLinks) client.lstat(source) else client.stat(source)
        } catch (e: ClientException) {
            throw e.toFileSystemException(source.toString())
        }
        if (!sourceAttributes.has(FileAttributes.Flag.MODE)) {
            throw FileSystemException(
                source.toString(),
                null,
                "Missing SSH_FILEXFER_ATTR_PERMISSIONS"
            )
        }
        val targetAttributes = try {
            client.lstat(target)
        } catch (e: ClientException) {
            val exception = e.toFileSystemException(target.toString())
            if (exception !is NoSuchFileException) {
                throw exception
            }
            // Ignored.
            null
        }
        val sourceSize = if (sourceAttributes.has(FileAttributes.Flag.SIZE)) {
            sourceAttributes.size
        } else {
            0
        }
        if (targetAttributes != null) {
            if (source == target) {
                copyOptions.progressListener?.invoke(sourceSize)
                return
            }
            if (!copyOptions.replaceExisting) {
                throw FileAlreadyExistsException(source.toString(), target.toString(), null)
            }
            // Symbolic links may not be supported so we cannot simply delete the target here.
        }
        val sourceType = sourceAttributes.type
        val sourceModeAttributes = FileAttributes.Builder()
            .apply {
                if (sourceAttributes.has(FileAttributes.Flag.MODE)) {
                    withPermissions(sourceAttributes.mode.mask)
                }
            }
            .build()
        when (sourceType) {
            FileMode.Type.REGULAR -> {
                // A replacement is written beside the target and renamed over it once complete,
                // so that a failed transfer never leaves the user with neither file.
                val isReplacing = targetAttributes != null
                val writeTarget = if (isReplacing) {
                    target.replacementSibling() as SftpPath
                } else {
                    target
                }
                val sourceInputStream = try {
                    client.openByteChannel(source, enumSetOf(OpenMode.READ), FileAttributes.EMPTY)
                } catch (e: ClientException) {
                    throw e.toFileSystemException(source.toString())
                }.newInputStream()
                try {
                    val targetFlags = enumSetOf(
                        OpenMode.WRITE,
                        OpenMode.TRUNC,
                        OpenMode.CREAT,
                        OpenMode.EXCL
                    )
                    val targetOutputStream = try {
                        client.openByteChannel(writeTarget, targetFlags, sourceModeAttributes)
                    } catch (e: ClientException) {
                        throw e.toFileSystemException(writeTarget.toString())
                    }.newOutputStream()
                    var successful = false
                    try {
                        sourceInputStream.copyTo(
                            targetOutputStream,
                            copyOptions.progressIntervalMillis,
                            copyOptions.progressListener
                        )
                        successful = true
                    } finally {
                        try {
                            targetOutputStream.close()
                        } catch (e: IOException) {
                            throw ClientException(e).toFileSystemException(writeTarget.toString())
                        } finally {
                            if (!successful) {
                                writeTarget.removeLogging()
                            }
                        }
                    }
                } finally {
                    try {
                        sourceInputStream.close()
                    } catch (e: IOException) {
                        throw ClientException(e).toFileSystemException(source.toString())
                    }
                }
                if (isReplacing) {
                    try {
                        // SFTP rename does not overwrite, so the old file goes first; its
                        // replacement is already complete on the server at this point.
                        try {
                            client.remove(target)
                        } catch (e: ClientException) {
                            val exception = e.toFileSystemException(target.toString())
                            if (exception !is NoSuchFileException) {
                                throw exception
                            }
                        }
                        try {
                            client.rename(writeTarget, target)
                        } catch (e: ClientException) {
                            throw e.toFileSystemException(writeTarget.toString(), target.toString())
                        }
                    } catch (e: IOException) {
                        writeTarget.removeLogging()
                        throw e
                    }
                }
            }

            FileMode.Type.DIRECTORY -> {
                if (targetAttributes != null) {
                    try {
                        client.remove(target)
                    } catch (e: ClientException) {
                        val exception = e.toFileSystemException(target.toString())
                        if (exception !is NoSuchFileException) {
                            throw exception
                        }
                    }
                }
                try {
                    client.mkdir(target, sourceModeAttributes)
                } catch (e: ClientException) {
                    throw e.toFileSystemException(target.toString())
                }
                copyOptions.progressListener?.invoke(sourceSize)
            }

            FileMode.Type.SYMLINK -> {
                val sourceTarget = try {
                    client.readlink(source)
                } catch (e: ClientException) {
                    throw e.toFileSystemException(source.toString())
                }
                try {
                    client.symlink(target, sourceTarget)
                } catch (e: ClientException) {
                    val exception = e.toFileSystemException(target.toString())
                    if (exception is FileAlreadyExistsException && copyOptions.replaceExisting) {
                        try {
                            client.remove(target)
                        } catch (e2: ClientException) {
                            if (e2.toFileSystemException(target.toString())
                                    !is NoSuchFileException
                            ) {
                                e2.addSuppressed(exception)
                                throw e2.toFileSystemException(target.toString())
                            }
                        }
                        try {
                            client.symlink(target, sourceTarget)
                        } catch (e2: ClientException) {
                            e2.addSuppressed(exception)
                            throw e2.toFileSystemException(target.toString())
                        }
                    }
                    throw e.toFileSystemException(target.toString())
                }
                copyOptions.progressListener?.invoke(sourceSize)
            }

            else -> throw FileSystemException(source.toString(), null, "type $sourceType")
        }
        // We don't take error when copying attribute fatal, so errors will only be logged from now
        // on.
        if (sourceType != FileMode.Type.SYMLINK) {
            val attributes = FileAttributes.Builder()
                .apply {
                    if (copyOptions.copyAttributes &&
                        sourceAttributes.has(FileAttributes.Flag.UIDGID)
                    ) {
                        withUIDGID(sourceAttributes.uid, sourceAttributes.gid)
                    }
                    if (sourceAttributes.type != FileMode.Type.SYMLINK &&
                        sourceAttributes.has(FileAttributes.Flag.MODE)
                    ) {
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
    }

    private fun SftpPath.removeLogging() {
        try {
            client.remove(this)
        } catch (e: ClientException) {
            e.printStackTrace()
        }
    }

    @Throws(IOException::class)
    fun move(source: SftpPath, target: SftpPath, copyOptions: CopyOptions) {
        val sourceAttributes = try {
            client.lstat(source)
        } catch (e: ClientException) {
            throw e.toFileSystemException(source.toString())
        }
        if (!sourceAttributes.has(FileAttributes.Flag.MODE)) {
            throw FileSystemException(
                source.toString(),
                null,
                "Missing SSH_FILEXFER_ATTR_PERMISSIONS"
            )
        }
        val targetAttributes = try {
            client.lstat(target)
        } catch (e: ClientException) {
            val exception = e.toFileSystemException(target.toString())
            if (exception !is NoSuchFileException) {
                throw exception
            }
            // Ignored.
            null
        }
        val sourceSize = if (sourceAttributes.has(FileAttributes.Flag.SIZE)) {
            sourceAttributes.size
        } else {
            0
        }
        if (targetAttributes != null) {
            if (source == target) {
                copyOptions.progressListener?.invoke(sourceSize)
                return
            }
            if (!copyOptions.replaceExisting) {
                throw FileAlreadyExistsException(source.toString(), target.toString(), null)
            }
            try {
                client.remove(target)
            } catch (e: ClientException) {
                throw e.toFileSystemException(target.toString())
            }
        }
        var renameSuccessful = false
        try {
            client.rename(source, target)
            renameSuccessful = true
        } catch (e: ClientException) {
            if (copyOptions.atomicMove) {
                throw e.toFileSystemException(source.toString(), target.toString())
            }
            // Ignored.
        }
        if (renameSuccessful) {
            copyOptions.progressListener?.invoke(sourceSize)
            return
        }
        if (copyOptions.atomicMove) {
            throw AssertionError()
        }
        var copyOptions = copyOptions
        if (!copyOptions.copyAttributes || !copyOptions.noFollowLinks) {
            copyOptions = CopyOptions(
                copyOptions.replaceExisting,
                true,
                false,
                true,
                copyOptions.progressIntervalMillis,
                copyOptions.progressListener
            )
        }
        copy(source, target, copyOptions)
        try {
            client.remove(source)
        } catch (e: ClientException) {
            if (e.toFileSystemException(source.toString()) !is NoSuchFileException) {
                try {
                    client.remove(target)
                } catch (e2: ClientException) {
                    e.addSuppressed(e2.toFileSystemException(target.toString()))
                }
            }
            throw e.toFileSystemException(source.toString())
        }
    }
}
