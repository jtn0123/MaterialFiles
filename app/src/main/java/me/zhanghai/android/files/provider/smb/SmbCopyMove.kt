/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.smb

import com.hierynomus.msdtyp.FileTime
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileBasicInformation
import com.hierynomus.protocol.commons.EnumWithValue
import java.io.InterruptedIOException
import java8.nio.file.FileSystemException
import java8.nio.file.NoSuchFileException
import me.zhanghai.android.files.provider.common.AbstractCopyMove
import me.zhanghai.android.files.provider.common.CopyOptions
import me.zhanghai.android.files.provider.common.replacementSibling
import me.zhanghai.android.files.provider.smb.client.Client
import me.zhanghai.android.files.provider.smb.client.ClientException
import me.zhanghai.android.files.provider.smb.client.FileInformation
import me.zhanghai.android.files.provider.smb.client.getPathInformation
import me.zhanghai.android.files.provider.smb.client.setFileInformation
import me.zhanghai.android.files.util.enumSetOf
import me.zhanghai.android.files.util.hasBits
import me.zhanghai.android.files.util.logWarning

internal object SmbCopyMove : AbstractCopyMove<SmbPath, FileInformation>() {
    override fun readAttributes(path: SmbPath, noFollowLinks: Boolean): FileInformation {
        val information = try {
            client.getPathInformation(path, noFollowLinks)
        } catch (e: ClientException) {
            throw e.toFileSystemException(path.toString())
        }
        return information as? FileInformation
            ?: throw FileSystemException(path.toString(), null, "Cannot copy shares")
    }

    override fun readAttributesOrNull(path: SmbPath): FileInformation? {
        val information = try {
            client.getPathInformation(path, true)
        } catch (e: ClientException) {
            val exception = e.toFileSystemException(path.toString())
            if (exception !is NoSuchFileException) {
                throw exception
            }
            return null
        }
        return information as? FileInformation
            ?: throw FileSystemException(path.toString(), null, "Cannot copy shares")
    }

    override fun isSameFile(
        source: SmbPath,
        sourceAttributes: FileInformation,
        target: SmbPath,
        targetAttributes: FileInformation
    ): Boolean =
        SmbFileKey(source, sourceAttributes.fileId) == SmbFileKey(target, targetAttributes.fileId)

    override fun getFileType(attributes: FileInformation): FileType = when {
        attributes.fileAttributes.hasBits(FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.value) ->
            FileType.SYMBOLIC_LINK

        attributes.fileAttributes.hasBits(FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) ->
            FileType.DIRECTORY

        else -> FileType.REGULAR_FILE
    }

    override fun getSize(attributes: FileInformation): Long = attributes.endOfFile

    override fun copyRegularFile(
        source: SmbPath,
        sourceAttributes: FileInformation,
        target: SmbPath,
        copyOptions: CopyOptions
    ) {
        try {
            client.copyFile(
                source,
                target,
                copyOptions.copyAttributes,
                copyOptions.noFollowLinks,
                copyOptions.progressIntervalMillis,
                copyOptions.progressListener
            )
        } catch (e: ClientException) {
            (e.cause as? InterruptedIOException)?.let { throw it }
            e.maybeThrowInvalidFileNameException(target.toString())
            throw e.toFileSystemException(target.toString())
        }
    }

    override fun createDirectory(
        target: SmbPath,
        sourceAttributes: FileInformation,
        copyOptions: CopyOptions
    ) {
        try {
            client.createDirectory(
                target,
                sourceAttributes.attributesToCopy(copyOptions.copyAttributes)
            )
        } catch (e: ClientException) {
            e.maybeThrowInvalidFileNameException(target.toString())
            throw e.toFileSystemException(target.toString())
        }
    }

    override fun copySymbolicLink(
        source: SmbPath,
        sourceAttributes: FileInformation,
        target: SmbPath,
        copyOptions: CopyOptions
    ) {
        val sourceReparseData = try {
            client.readSymbolicLink(source)
        } catch (e: ClientException) {
            throw e.toFileSystemException(source.toString())
        }
        try {
            client.createSymbolicLink(
                target,
                sourceReparseData,
                sourceAttributes.attributesToCopy(copyOptions.copyAttributes)
            )
        } catch (e: ClientException) {
            e.maybeThrowInvalidFileNameException(target.toString())
            throw e.toFileSystemException(target.toString())
        }
    }

    override fun delete(path: SmbPath) {
        try {
            client.delete(path)
        } catch (e: ClientException) {
            val exception = e.toFileSystemException(path.toString())
            if (exception !is NoSuchFileException) {
                throw exception
            }
        }
    }

    override fun replacementSibling(target: SmbPath): SmbPath = target.replacementSibling()

    // The client renames with FILE_RENAME_INFORMATION.ReplaceIfExists set.
    override fun rename(source: SmbPath, target: SmbPath, replaceExisting: Boolean) {
        try {
            client.rename(source, target)
        } catch (e: ClientException) {
            e.maybeThrowAtomicMoveNotSupportedException(source.toString(), target.toString())
            e.maybeThrowInvalidFileNameException(target.toString())
            throw e.toFileSystemException(source.toString(), target.toString())
        }
    }

    override fun copyAttributes(
        source: SmbPath,
        sourceAttributes: FileInformation,
        target: SmbPath,
        copyOptions: CopyOptions
    ) {
        // TODO: Copy SecurityDescriptor.
        // TODO: Change modified time last?
        try {
            val fileInformation = FileBasicInformation(
                if (copyOptions.copyAttributes) sourceAttributes.creationTime else FileTime(0),
                if (copyOptions.copyAttributes) sourceAttributes.lastAccessTime else FileTime(0),
                sourceAttributes.lastWriteTime,
                if (copyOptions.copyAttributes) sourceAttributes.changeTime else FileTime(0),
                0
            )
            client.setFileInformation(target, true, fileInformation)
        } catch (e: ClientException) {
            e.logWarning("SmbCopyMove", "copyAttributes($source)")
        }
        // TODO: Copy FileFullEaInformation.
    }

    private fun FileInformation.attributesToCopy(copyAttributes: Boolean): Set<FileAttributes> =
        if (copyAttributes) {
            EnumWithValue.EnumUtils.toEnumSet(fileAttributes, FileAttributes::class.java)
        } else {
            enumSetOf(FileAttributes.FILE_ATTRIBUTE_NORMAL)
        }
}
