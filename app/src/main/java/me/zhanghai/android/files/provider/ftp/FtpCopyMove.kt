/*
 * Copyright (c) 2022 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.ftp

import java.io.IOException
import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.NoSuchFileException
import java8.nio.file.StandardCopyOption
import me.zhanghai.android.files.provider.common.CopyOptions
import me.zhanghai.android.files.provider.common.copyTo
import me.zhanghai.android.files.provider.ftp.client.Client

internal object FtpCopyMove {
    @Throws(IOException::class)
    fun copy(source: FtpPath, target: FtpPath, copyOptions: CopyOptions) {
        if (copyOptions.atomicMove) {
            throw UnsupportedOperationException(StandardCopyOption.ATOMIC_MOVE.toString())
        }
        val sourceFile = try {
            client.listFile(source, copyOptions.noFollowLinks)
        } catch (e: IOException) {
            throw e.toFileSystemExceptionForFtp(source.toString())
        }
        val targetFile = try {
            client.listFileOrNull(target, true)
        } catch (e: IOException) {
            throw e.toFileSystemExceptionForFtp(target.toString())
        }
        val sourceSize = sourceFile.size
        if (targetFile != null) {
            if (source == target) {
                copyOptions.progressListener?.invoke(sourceSize)
                return
            }
            if (!copyOptions.replaceExisting) {
                throw FileAlreadyExistsException(source.toString(), target.toString(), null)
            }
            try {
                client.delete(target, targetFile.isDirectory)
            } catch (e: IOException) {
                throw e.toFileSystemExceptionForFtp(target.toString())
            }
        }
        when {
            sourceFile.isDirectory -> {
                try {
                    client.createDirectory(target)
                } catch (e: IOException) {
                    throw e.toFileSystemExceptionForFtp(target.toString())
                }
                copyOptions.progressListener?.invoke(sourceSize)
            }

            sourceFile.isSymbolicLink ->
                throw UnsupportedOperationException("Cannot copy symbolic links")

            else -> {
                val sourceInputStream = try {
                    client.retrieveFile(source)
                } catch (e: IOException) {
                    throw e.toFileSystemExceptionForFtp(source.toString())
                }
                try {
                    val targetOutputStream = try {
                        client.storeFile(target)
                    } catch (e: IOException) {
                        throw e.toFileSystemExceptionForFtp(target.toString())
                    }
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
                            throw e.toFileSystemExceptionForFtp(target.toString())
                        } finally {
                            if (!successful) {
                                try {
                                    client.delete(target, sourceFile.isDirectory)
                                } catch (e: IOException) {
                                    e.printStackTrace()
                                }
                            }
                        }
                    }
                } finally {
                    try {
                        sourceInputStream.close()
                    } catch (e: IOException) {
                        throw e.toFileSystemExceptionForFtp(source.toString())
                    }
                }
            }
        }
        // We don't take error when copying attribute fatal, so errors will only be logged from now
        // on.
        if (!sourceFile.isSymbolicLink) {
            val timestamp = sourceFile.timestamp
            if (timestamp != null) {
                try {
                    client.setLastModifiedTime(target, timestamp.toInstant())
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }
    }

    @Throws(IOException::class)
    fun move(source: FtpPath, target: FtpPath, copyOptions: CopyOptions) {
        val sourceFile = try {
            client.listFile(source, copyOptions.noFollowLinks)
        } catch (e: IOException) {
            throw e.toFileSystemExceptionForFtp(source.toString())
        }
        val targetFile = try {
            client.listFileOrNull(target, true)
        } catch (e: IOException) {
            throw e.toFileSystemExceptionForFtp(target.toString())
        }
        val sourceSize = sourceFile.size
        if (targetFile != null) {
            if (source == target) {
                copyOptions.progressListener?.invoke(sourceSize)
                return
            }
            if (!copyOptions.replaceExisting) {
                throw FileAlreadyExistsException(source.toString(), target.toString(), null)
            }
            try {
                client.delete(target, targetFile.isDirectory)
            } catch (e: IOException) {
                throw e.toFileSystemExceptionForFtp(target.toString())
            }
        }
        var renameSuccessful = false
        try {
            client.renameFile(source, target)
            renameSuccessful = true
        } catch (e: IOException) {
            if (copyOptions.atomicMove) {
                throw e.toFileSystemExceptionForFtp(source.toString(), target.toString())
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
            client.delete(source, sourceFile.isDirectory)
        } catch (e: IOException) {
            if (e.toFileSystemExceptionForFtp(source.toString()) !is NoSuchFileException) {
                try {
                    client.delete(target, sourceFile.isDirectory)
                } catch (e2: IOException) {
                    e.addSuppressed(e2.toFileSystemExceptionForFtp(target.toString()))
                }
            }
            throw e.toFileSystemExceptionForFtp(source.toString())
        }
    }
}
