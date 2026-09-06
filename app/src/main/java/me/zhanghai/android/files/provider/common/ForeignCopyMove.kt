/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import java.io.IOException
import java8.nio.file.CopyOption
import java8.nio.file.LinkOption
import java8.nio.file.NoSuchFileException
import java8.nio.file.Path
import java8.nio.file.StandardCopyOption
import java8.nio.file.StandardOpenOption
import java8.nio.file.attribute.BasicFileAttributeView
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.FileTime

/**
 * Copy and move between two different providers, through the provider-neutral [Path] API. A
 * move is always a copy followed by a delete here, since nothing can rename across providers.
 */
internal object ForeignCopyMove : AbstractCopyMove<Path, BasicFileAttributes>() {
    @Throws(IOException::class)
    fun copy(source: Path, target: Path, vararg options: CopyOption) {
        copy(source, target, options.toCopyOptions())
    }

    @Throws(IOException::class)
    fun move(source: Path, target: Path, vararg options: CopyOption) {
        move(source, target, options.toCopyOptions())
    }

    override fun readAttributes(path: Path, noFollowLinks: Boolean): BasicFileAttributes =
        path.readAttributes(BasicFileAttributes::class.java, *linkOptions(noFollowLinks))

    override fun readAttributesOrNull(path: Path): BasicFileAttributes? = try {
        path.readAttributes(BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (e: NoSuchFileException) {
        null
    }

    // Two paths on different providers are never the same file.
    override fun isSameFile(
        source: Path,
        sourceAttributes: BasicFileAttributes,
        target: Path,
        targetAttributes: BasicFileAttributes
    ): Boolean = false

    override fun getFileType(attributes: BasicFileAttributes): FileType = when {
        attributes.isRegularFile -> FileType.REGULAR_FILE
        attributes.isDirectory -> FileType.DIRECTORY
        attributes.isSymbolicLink -> FileType.SYMBOLIC_LINK
        else -> FileType.OTHER
    }

    override fun getSize(attributes: BasicFileAttributes): Long = attributes.size()

    override fun copyRegularFile(
        source: Path,
        sourceAttributes: BasicFileAttributes,
        target: Path,
        copyOptions: CopyOptions
    ) {
        source.newInputStream(*linkOptions(copyOptions.noFollowLinks)).use { inputStream ->
            target.newOutputStream(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                .use { outputStream ->
                    inputStream.copyTo(
                        outputStream,
                        copyOptions.progressIntervalMillis,
                        copyOptions.progressListener
                    )
                }
        }
    }

    override fun createDirectory(
        target: Path,
        sourceAttributes: BasicFileAttributes,
        copyOptions: CopyOptions
    ) {
        target.createDirectory()
    }

    override fun copySymbolicLink(
        source: Path,
        sourceAttributes: BasicFileAttributes,
        target: Path,
        copyOptions: CopyOptions
    ) {
        target.createSymbolicLink(source.readSymbolicLink())
    }

    override fun delete(path: Path) {
        path.deleteIfExists()
    }

    override fun replacementSibling(target: Path): Path = target.replacementSibling()

    override val canRename: Boolean
        get() = false

    // Only reached for a replacement, whose sibling is on the same provider as the target.
    override fun rename(source: Path, target: Path, replaceExisting: Boolean) {
        if (replaceExisting) {
            source.moveTo(target, StandardCopyOption.REPLACE_EXISTING)
        } else {
            source.moveTo(target)
        }
    }

    override fun copyAttributes(
        source: Path,
        sourceAttributes: BasicFileAttributes,
        target: Path,
        copyOptions: CopyOptions
    ) {
        val targetAttributeView = target.getFileAttributeView(BasicFileAttributeView::class.java)!!
        val lastModifiedTime = sourceAttributes.lastModifiedTime()
            .takeIf { it != FileTime::class.EPOCH }
        val lastAccessTime = if (copyOptions.copyAttributes) {
            sourceAttributes.lastAccessTime().takeIf { it != FileTime::class.EPOCH }
        } else {
            null
        }
        val creationTime = if (copyOptions.copyAttributes) {
            sourceAttributes.creationTime().takeIf { it != FileTime::class.EPOCH }
        } else {
            null
        }
        try {
            targetAttributeView.setTimes(lastModifiedTime, lastAccessTime, creationTime)
        } catch (e: IOException) {
            e.printStackTrace()
        } catch (e: UnsupportedOperationException) {
            e.printStackTrace()
        }
    }

    private fun linkOptions(noFollowLinks: Boolean): Array<LinkOption> =
        if (noFollowLinks) arrayOf(LinkOption.NOFOLLOW_LINKS) else emptyArray()
}
