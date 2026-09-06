/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.webdav

import at.bitfire.dav4jvm.Response
import at.bitfire.dav4jvm.exception.DavException
import java8.nio.file.NoSuchFileException
import me.zhanghai.android.files.provider.common.AbstractCopyMove
import me.zhanghai.android.files.provider.common.CopyOptions
import me.zhanghai.android.files.provider.common.copyTo
import me.zhanghai.android.files.provider.common.replacementSibling
import me.zhanghai.android.files.provider.webdav.client.Client
import me.zhanghai.android.files.provider.webdav.client.isDirectory
import me.zhanghai.android.files.provider.webdav.client.isSymbolicLink
import me.zhanghai.android.files.provider.webdav.client.lastModifiedTime
import me.zhanghai.android.files.provider.webdav.client.size
import me.zhanghai.android.files.util.logWarning

internal object WebDavCopyMove : AbstractCopyMove<WebDavPath, Response>() {
    override fun readAttributes(path: WebDavPath, noFollowLinks: Boolean): Response = try {
        client.findProperties(path, noFollowLinks)
    } catch (e: DavException) {
        throw e.toFileSystemException(path.toString())
    }

    override fun readAttributesOrNull(path: WebDavPath): Response? = try {
        client.findPropertiesOrNull(path, true)
    } catch (e: DavException) {
        throw e.toFileSystemException(path.toString())
    }

    override fun isSameFile(
        source: WebDavPath,
        sourceAttributes: Response,
        target: WebDavPath,
        targetAttributes: Response
    ): Boolean = source == target

    override fun getFileType(attributes: Response): FileType = when {
        attributes.isDirectory -> FileType.DIRECTORY
        attributes.isSymbolicLink -> FileType.SYMBOLIC_LINK
        else -> FileType.REGULAR_FILE
    }

    override fun getSize(attributes: Response): Long = attributes.size

    override fun copyRegularFile(
        source: WebDavPath,
        sourceAttributes: Response,
        target: WebDavPath,
        copyOptions: CopyOptions
    ) {
        val sourceInputStream = try {
            client.get(source)
        } catch (e: DavException) {
            throw e.toFileSystemException(source.toString())
        }
        try {
            val targetOutputStream = try {
                client.put(target)
            } catch (e: DavException) {
                throw e.toFileSystemException(target.toString())
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
                } catch (e: DavException) {
                    throw e.toFileSystemException(target.toString())
                }
            }
        } finally {
            try {
                sourceInputStream.close()
            } catch (e: DavException) {
                throw e.toFileSystemException(source.toString())
            }
        }
    }

    override fun createDirectory(
        target: WebDavPath,
        sourceAttributes: Response,
        copyOptions: CopyOptions
    ) {
        try {
            client.makeCollection(target)
        } catch (e: DavException) {
            throw e.toFileSystemException(target.toString())
        }
    }

    override fun copySymbolicLink(
        source: WebDavPath,
        sourceAttributes: Response,
        target: WebDavPath,
        copyOptions: CopyOptions
    ): Unit = throw UnsupportedOperationException("Cannot copy symbolic links")

    override fun delete(path: WebDavPath) {
        try {
            client.delete(path)
        } catch (e: DavException) {
            val exception = e.toFileSystemException(path.toString())
            if (exception !is NoSuchFileException) {
                throw exception
            }
        }
    }

    override fun replacementSibling(target: WebDavPath): WebDavPath =
        target.replacementSibling() as WebDavPath

    override fun rename(source: WebDavPath, target: WebDavPath, replaceExisting: Boolean) {
        try {
            client.move(source, target, overwrite = replaceExisting)
        } catch (e: DavException) {
            throw e.toFileSystemException(source.toString(), target.toString())
        }
    }

    override fun copyAttributes(
        source: WebDavPath,
        sourceAttributes: Response,
        target: WebDavPath,
        copyOptions: CopyOptions
    ) {
        val lastModifiedTime = sourceAttributes.lastModifiedTime ?: return
        try {
            client.setLastModifiedTime(target, lastModifiedTime)
        } catch (e: DavException) {
            e.logWarning("WebDavCopyMove", "copyAttributes($source)")
        }
    }
}
