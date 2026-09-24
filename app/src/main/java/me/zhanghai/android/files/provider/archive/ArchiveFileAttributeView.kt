/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive

import java.io.IOException
import java8.nio.file.Path
import java8.nio.file.attribute.FileTime
import me.zhanghai.android.files.provider.common.ByteString
import me.zhanghai.android.files.provider.common.PosixFileAttributeView
import me.zhanghai.android.files.provider.common.PosixFileModeBit
import me.zhanghai.android.files.provider.common.PosixGroup
import me.zhanghai.android.files.provider.common.PosixUser

internal class ArchiveFileAttributeView(private val path: Path) : PosixFileAttributeView {
    override fun name(): String = NAME

    @Throws(IOException::class)
    override fun readAttributes(): ArchiveFileAttributes {
        val fileSystem = path.fileSystem as ArchiveFileSystem
        val entry = fileSystem.getEntry(path)
        return ArchiveFileAttributes.from(fileSystem.archiveFile, entry)
    }

    override fun setTimes(
        lastModifiedTime: FileTime?,
        lastAccessTime: FileTime?,
        createTime: FileTime?
    ): Unit = throw UnsupportedOperationException()

    override fun setOwner(owner: PosixUser): Unit = throw UnsupportedOperationException()

    override fun setGroup(group: PosixGroup): Unit = throw UnsupportedOperationException()

    override fun setMode(mode: Set<PosixFileModeBit>): Unit = throw UnsupportedOperationException()

    override fun setSeLinuxContext(context: ByteString): Unit =
        throw UnsupportedOperationException()

    override fun restoreSeLinuxContext(): Unit = throw UnsupportedOperationException()

    companion object {
        private val NAME = ArchiveFileSystemProvider.scheme

        val SUPPORTED_NAMES = setOf("basic", "posix", NAME)
    }
}
