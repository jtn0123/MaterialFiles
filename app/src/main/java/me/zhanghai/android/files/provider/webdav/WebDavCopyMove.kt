/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.webdav

import at.bitfire.dav4jvm.exception.DavException
import java.io.IOException
import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.NoSuchFileException
import java8.nio.file.StandardCopyOption
import me.zhanghai.android.files.provider.common.CopyOptions
import me.zhanghai.android.files.provider.common.copyTo
import me.zhanghai.android.files.provider.common.replacementSibling
import me.zhanghai.android.files.provider.webdav.client.Client
import me.zhanghai.android.files.provider.webdav.client.isDirectory
import me.zhanghai.android.files.provider.webdav.client.isSymbolicLink
import me.zhanghai.android.files.provider.webdav.client.lastModifiedTime
import me.zhanghai.android.files.provider.webdav.client.size

internal object WebDavCopyMove {
    @Throws(IOException::class)
    fun copy(source: WebDavPath, target: WebDavPath, copyOptions: CopyOptions) {
        if (copyOptions.atomicMove) {
            throw UnsupportedOperationException(StandardCopyOption.ATOMIC_MOVE.toString())
        }
        val sourceResponse = try {
            client.findProperties(source, copyOptions.noFollowLinks)
        } catch (e: DavException) {
            throw e.toFileSystemException(source.toString())
        }
        val targetFile = try {
            client.findPropertiesOrNull(target, true)
        } catch (e: DavException) {
            throw e.toFileSystemException(target.toString())
        }
        val sourceSize = sourceResponse.size
        if (targetFile != null) {
            if (source == target) {
                copyOptions.progressListener?.invoke(sourceSize)
                return
            }
            if (!copyOptions.replaceExisting) {
                throw FileAlreadyExistsException(source.toString(), target.toString(), null)
            }
            if (sourceResponse.isDirectory) {
                try {
                    client.delete(target)
                } catch (e: DavException) {
                    throw e.toFileSystemException(target.toString())
                }
            }
        }
        when {
            sourceResponse.isDirectory -> {
                try {
                    client.makeCollection(target)
                } catch (e: DavException) {
                    throw e.toFileSystemException(target.toString())
                }
                copyOptions.progressListener?.invoke(sourceSize)
            }

            sourceResponse.isSymbolicLink ->
                throw UnsupportedOperationException("Cannot copy symbolic links")

            else -> {
                // A replacement is written beside the target and moved over it once complete,
                // so that a failed transfer never leaves the user with neither file.
                val isReplacing = targetFile != null
                val writeTarget = if (isReplacing) {
                    target.replacementSibling() as WebDavPath
                } else {
                    target
                }
                val sourceInputStream = try {
                    client.get(source)
                } catch (e: DavException) {
                    throw e.toFileSystemException(source.toString())
                }
                try {
                    val targetOutputStream = try {
                        client.put(writeTarget)
                    } catch (e: DavException) {
                        throw e.toFileSystemException(writeTarget.toString())
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
                        } catch (e: DavException) {
                            throw e.toFileSystemException(writeTarget.toString())
                        } finally {
                            if (!successful) {
                                writeTarget.deleteLogging()
                            }
                        }
                    }
                } finally {
                    try {
                        sourceInputStream.close()
                    } catch (e: DavException) {
                        throw e.toFileSystemException(source.toString())
                    }
                }
                if (isReplacing) {
                    try {
                        client.move(writeTarget, target, overwrite = true)
                    } catch (e: DavException) {
                        writeTarget.deleteLogging()
                        throw e.toFileSystemException(writeTarget.toString(), target.toString())
                    }
                }
            }
        }
        // We don't take error when copying attribute fatal, so errors will only be logged from now
        // on.
        if (!sourceResponse.isSymbolicLink) {
            val lastModifiedTime = sourceResponse.lastModifiedTime
            if (lastModifiedTime != null) {
                try {
                    client.setLastModifiedTime(target, lastModifiedTime)
                } catch (e: DavException) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun WebDavPath.deleteLogging() {
        try {
            client.delete(this)
        } catch (e: DavException) {
            e.printStackTrace()
        }
    }

    @Throws(IOException::class)
    fun move(source: WebDavPath, target: WebDavPath, copyOptions: CopyOptions) {
        val sourceResponse = try {
            client.findProperties(source, copyOptions.noFollowLinks)
        } catch (e: DavException) {
            throw e.toFileSystemException(source.toString())
        }
        val targetResponse = try {
            client.findPropertiesOrNull(target, true)
        } catch (e: DavException) {
            throw e.toFileSystemException(target.toString())
        }
        val sourceSize = sourceResponse.size
        if (targetResponse != null) {
            if (source == target) {
                copyOptions.progressListener?.invoke(sourceSize)
                return
            }
            if (!copyOptions.replaceExisting) {
                throw FileAlreadyExistsException(source.toString(), target.toString(), null)
            }
            try {
                client.delete(target)
            } catch (e: DavException) {
                throw e.toFileSystemException(target.toString())
            }
        }
        var renameSuccessful = false
        try {
            client.move(source, target)
            renameSuccessful = true
        } catch (e: DavException) {
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
            client.delete(source)
        } catch (e: DavException) {
            if (e.toFileSystemException(source.toString()) !is NoSuchFileException) {
                try {
                    client.delete(target)
                } catch (e2: DavException) {
                    e.addSuppressed(e2.toFileSystemException(target.toString()))
                }
            }
            throw e.toFileSystemException(source.toString())
        }
    }
}
