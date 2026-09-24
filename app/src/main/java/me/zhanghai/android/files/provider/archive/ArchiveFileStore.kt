/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive

import java.io.IOException
import java8.nio.file.Path
import java8.nio.file.attribute.FileAttributeView
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.guessFromPath
import me.zhanghai.android.files.provider.common.PosixFileStore
import me.zhanghai.android.files.provider.common.size

internal class ArchiveFileStore(private val archiveFile: Path) : PosixFileStore() {
    override fun refresh() {
        // Nothing is cached: the sizes are read from the archive file on each call.
    }

    override fun name(): String = archiveFile.toString()

    override fun type(): String = MimeType.guessFromPath(archiveFile.toString()).value

    override fun isReadOnly(): Boolean = true

    @Throws(IOException::class)
    override fun setReadOnly(readOnly: Boolean): Unit = throw UnsupportedOperationException()

    @Throws(IOException::class)
    override fun getTotalSpace(): Long = archiveFile.size()

    override fun getUsableSpace(): Long = 0

    override fun getUnallocatedSpace(): Long = 0

    override fun supportsFileAttributeView(type: Class<out FileAttributeView>): Boolean =
        ArchiveFileSystemProvider.supportsFileAttributeView(type)

    override fun supportsFileAttributeView(name: String): Boolean =
        name in ArchiveFileAttributeView.SUPPORTED_NAMES
}
