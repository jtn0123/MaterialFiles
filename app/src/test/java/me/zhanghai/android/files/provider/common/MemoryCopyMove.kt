/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import java.io.IOException
import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.NoSuchFileException

internal class MemoryFile(
    val type: Type,
    var content: String = "",
    var linkTarget: String = "",
    var mtime: Long = 0
) {
    enum class Type { REGULAR, DIRECTORY, LINK, OTHER }

    val isDirectory: Boolean
        get() = type == Type.DIRECTORY

    val size: Long
        get() = content.length.toLong()

    companion object {
        fun regular(content: String, mtime: Long = 0) =
            MemoryFile(Type.REGULAR, content, mtime = mtime)

        fun directory() = MemoryFile(Type.DIRECTORY)

        fun link(target: String) = MemoryFile(Type.LINK, linkTarget = target)
    }
}

/**
 * Paths are plain strings; [failOn] names the operation that throws, and the `*Unsupported`
 * flags make it throw [UnsupportedOperationException] instead of [IOException], which is what a
 * provider does for something its server cannot do at all.
 */
internal class MemoryCopyMove : AbstractCopyMove<String, MemoryFile>() {
    val files = mutableMapOf<String, MemoryFile>()
    val log = mutableListOf<String>()
    val typedCalls = mutableListOf<String>()
    val progress = mutableListOf<Long>()
    var failOn: String? = null
    var failDeleteOf: String? = null
    var failDeleteUnsupported = false
    var renameUnsupported = false
    var targetAppearsBeforeSymbolicLink = false
    var canRenameForTest = true

    override fun readAttributes(path: String, noFollowLinks: Boolean): MemoryFile =
        files[path] ?: throw NoSuchFileException(path)

    override fun readAttributesOrNull(path: String): MemoryFile? = files[path]

    override fun isSameFile(
        source: String,
        sourceAttributes: MemoryFile,
        target: String,
        targetAttributes: MemoryFile
    ): Boolean = source == target

    override fun getFileType(attributes: MemoryFile): FileType = when (attributes.type) {
        MemoryFile.Type.REGULAR -> FileType.REGULAR_FILE
        MemoryFile.Type.DIRECTORY -> FileType.DIRECTORY
        MemoryFile.Type.LINK -> FileType.SYMBOLIC_LINK
        MemoryFile.Type.OTHER -> FileType.OTHER
    }

    override fun getSize(attributes: MemoryFile): Long = attributes.size

    override fun copyRegularFile(
        source: String,
        sourceAttributes: MemoryFile,
        target: String,
        copyOptions: CopyOptions
    ) {
        log += "write $target"
        check(target !in files) { "$target exists" }
        // A partial file is what a failure leaves behind.
        files[target] = MemoryFile.regular(sourceAttributes.content.take(1))
        if (failOn == "write") {
            throw IOException("write failed")
        }
        if (failOn == "write-unsupported") {
            throw UnsupportedOperationException("write unsupported")
        }
        files[target] = MemoryFile.regular(sourceAttributes.content)
        copyOptions.progressListener?.invoke(sourceAttributes.size)
    }

    override fun createDirectory(
        target: String,
        sourceAttributes: MemoryFile,
        copyOptions: CopyOptions
    ) {
        log += "mkdir $target"
        if (target in files) {
            throw FileAlreadyExistsException(target)
        }
        files[target] = MemoryFile.directory()
    }

    override fun copySymbolicLink(
        source: String,
        sourceAttributes: MemoryFile,
        target: String,
        copyOptions: CopyOptions
    ) {
        log += "symlink $target"
        if (targetAppearsBeforeSymbolicLink) {
            throw FileAlreadyExistsException(target)
        }
        if (target in files) {
            throw FileAlreadyExistsException(target)
        }
        files[target] = MemoryFile.link(sourceAttributes.linkTarget)
    }

    override fun delete(path: String) {
        log += "delete $path"
        if (path == failDeleteOf) {
            if (failDeleteUnsupported) {
                throw UnsupportedOperationException("delete unsupported")
            }
            throw IOException("delete failed")
        }
        files -= path
    }

    override fun delete(path: String, fileType: FileType) {
        typedCalls += "delete $path $fileType"
        delete(path)
    }

    override fun replacementSibling(target: String): String = "$target.part"

    override val canRename: Boolean
        get() = canRenameForTest

    override fun rename(
        source: String,
        target: String,
        fileType: FileType,
        replaceExisting: Boolean
    ) {
        typedCalls += "rename $source $fileType"
        rename(source, target, replaceExisting)
    }

    override fun rename(source: String, target: String, replaceExisting: Boolean) {
        log += "rename $source $target $replaceExisting"
        if (renameUnsupported) {
            throw UnsupportedOperationException("rename unsupported")
        }
        if (failOn == "rename") {
            throw IOException("rename failed")
        }
        if (!replaceExisting && target in files) {
            throw FileAlreadyExistsException(target)
        }
        files[target] = files.remove(source) ?: throw NoSuchFileException(source)
    }

    override fun copyAttributes(
        source: String,
        sourceAttributes: MemoryFile,
        target: String,
        copyOptions: CopyOptions
    ) {
        files[target]?.mtime = sourceAttributes.mtime
    }
}
