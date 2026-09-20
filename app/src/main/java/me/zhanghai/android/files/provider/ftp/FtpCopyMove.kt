/*
 * Copyright (c) 2022 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.ftp

import java.io.IOException
import java8.nio.file.NoSuchFileException
import me.zhanghai.android.files.provider.common.AbstractCopyMove
import me.zhanghai.android.files.provider.common.CopyOptions
import me.zhanghai.android.files.provider.common.copyTo
import me.zhanghai.android.files.provider.common.replacementSibling
import me.zhanghai.android.files.provider.ftp.client.Client
import me.zhanghai.android.files.util.logWarning
import org.apache.commons.net.ftp.FTPFile

internal object FtpCopyMove : AbstractCopyMove<FtpPath, FTPFile>() {
    override fun readAttributes(path: FtpPath, noFollowLinks: Boolean): FTPFile = try {
        client.listFile(path, noFollowLinks)
    } catch (e: IOException) {
        throw e.toFileSystemExceptionForFtp(path.toString())
    }

    override fun readAttributesOrNull(path: FtpPath): FTPFile? = try {
        client.listFileOrNull(path, true)
    } catch (e: IOException) {
        throw e.toFileSystemExceptionForFtp(path.toString())
    }

    override fun isSameFile(
        source: FtpPath,
        sourceAttributes: FTPFile,
        target: FtpPath,
        targetAttributes: FTPFile
    ): Boolean = source == target

    override fun getFileType(attributes: FTPFile): FileType = when {
        attributes.isDirectory -> FileType.DIRECTORY
        attributes.isSymbolicLink -> FileType.SYMBOLIC_LINK
        else -> FileType.REGULAR_FILE
    }

    override fun getSize(attributes: FTPFile): Long = attributes.size

    override fun copyRegularFile(
        source: FtpPath,
        sourceAttributes: FTPFile,
        target: FtpPath,
        copyOptions: CopyOptions
    ) {
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
                    throw e.toFileSystemExceptionForFtp(target.toString())
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

    override fun createDirectory(
        target: FtpPath,
        sourceAttributes: FTPFile,
        copyOptions: CopyOptions
    ) {
        try {
            client.createDirectory(target)
        } catch (e: IOException) {
            throw e.toFileSystemExceptionForFtp(target.toString())
        }
    }

    override fun copySymbolicLink(
        source: FtpPath,
        sourceAttributes: FTPFile,
        target: FtpPath,
        copyOptions: CopyOptions
    ): Unit = throw UnsupportedOperationException("Cannot copy symbolic links")

    override fun delete(path: FtpPath) {
        try {
            client.delete(path)
        } catch (e: IOException) {
            val exception = e.toFileSystemExceptionForFtp(path.toString())
            if (exception !is NoSuchFileException) {
                throw exception
            }
        }
    }

    override fun replacementSibling(target: FtpPath): FtpPath = target.replacementSibling()

    // Whether RNTO overwrites depends on the server, so a target being replaced goes first.
    override fun rename(source: FtpPath, target: FtpPath, replaceExisting: Boolean) {
        if (replaceExisting) {
            delete(target)
        }
        try {
            client.renameFile(source, target)
        } catch (e: IOException) {
            throw e.toFileSystemExceptionForFtp(source.toString(), target.toString())
        }
    }

    override fun copyAttributes(
        source: FtpPath,
        sourceAttributes: FTPFile,
        target: FtpPath,
        copyOptions: CopyOptions
    ) {
        val timestamp = sourceAttributes.timestamp ?: return
        try {
            client.setLastModifiedTime(target, timestamp.toInstant())
        } catch (e: IOException) {
            e.logWarning("FtpCopyMove", "copyAttributes($source)")
        }
    }
}
