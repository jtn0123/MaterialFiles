/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.io.IOException
import java.io.InterruptedIOException
import java8.nio.file.DirectoryIteratorException
import java8.nio.file.FileVisitResult
import java8.nio.file.FileVisitor
import java8.nio.file.Files
import java8.nio.file.LinkOption
import java8.nio.file.Path
import java8.nio.file.SimpleFileVisitor
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.provider.archive.archiveFile
import me.zhanghai.android.files.provider.archive.createArchiveRootPath
import me.zhanghai.android.files.provider.archive.isArchivePath
import me.zhanghai.android.files.provider.common.PosixPrincipal
import me.zhanghai.android.files.provider.common.asByteStringListPath
import me.zhanghai.android.files.provider.common.getPath
import me.zhanghai.android.files.provider.common.newDirectoryStream
import me.zhanghai.android.files.provider.common.readAttributes
import me.zhanghai.android.files.util.asFileName
import me.zhanghai.android.files.util.getQuantityString

fun FileJob.getString(@StringRes stringRes: Int): String = service.getString(stringRes)

fun FileJob.getString(@StringRes stringRes: Int, vararg formatArguments: Any?): String =
    service.getString(stringRes, *formatArguments)

fun FileJob.getQuantityString(@PluralsRes pluralRes: Int, quantity: Int): String =
    service.getQuantityString(pluralRes, quantity)

fun FileJob.getQuantityString(
    @PluralsRes pluralRes: Int,
    quantity: Int,
    vararg formatArguments: Any?
): String = service.getQuantityString(pluralRes, quantity, *formatArguments)

internal fun FileJob.getFileName(path: Path): String = path.displayName

/** The file name, or the separator for a root, which has none. */
internal val Path.displayName: String
    get() = if (isAbsolute && nameCount == 0) fileSystem.separator else fileName.toString()

internal fun FileJob.getTargetFileName(source: Path): Path {
    if (source.isArchivePath) {
        val archiveFile = source.archiveFile.asByteStringListPath()
        val archiveRoot = archiveFile.createArchiveRootPath()
        if (source == archiveRoot) {
            return archiveFile.fileSystem.getPath(
                archiveFile.fileNameByteString!!.asFileName().baseName
            )
        }
    }
    return source.fileName
}

// The attributes for start path prefers following links, but falls back to not following.
// FileVisitResult returned from visitor may be ignored and always considered CONTINUE.
@Throws(IOException::class)
internal fun FileJob.walkFileTreeForSettingAttributes(
    start: Path,
    recursive: Boolean,
    visitor: FileVisitor<in Path>
): Path {
    val attributes = try {
        start.readAttributes(BasicFileAttributes::class.java)
    } catch (ignored: IOException) {
        try {
            start.readAttributes(BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (e: IOException) {
            visitor.visitFileFailed(start, e)
            return start
        }
    }
    if (!recursive || !attributes.isDirectory) {
        visitor.visitFile(start, attributes)
        return start
    }
    val directoryStream = try {
        start.newDirectoryStream()
    } catch (e: IOException) {
        visitor.visitFileFailed(start, e)
        return start
    }
    directoryStream.use {
        visitor.preVisitDirectory(start, attributes)
        try {
            directoryStream.forEach { Files.walkFileTree(it, visitor) }
        } catch (e: DirectoryIteratorException) {
            visitor.postVisitDirectory(start, e.cause)
            return start
        }
    }
    visitor.postVisitDirectory(start, null)
    return start
}

@Throws(InterruptedIOException::class)
internal fun FileJob.throwIfInterrupted() {
    if (Thread.interrupted()) {
        throw InterruptedIOException()
    }
}

@Throws(IOException::class)
internal fun FileJob.scan(
    sources: List<Path>,
    @PluralsRes notificationTitleRes: Int,
    actionAllInfo: ActionAllInfo = ActionAllInfo()
): ScanInfo {
    val scanInfo = countFiles(sources, actionAllInfo) {
        postScanNotification(it, notificationTitleRes)
    }
    postScanNotification(scanInfo, notificationTitleRes)
    return scanInfo
}

/**
 * Counts the files under [sources] and their total size, calling [onProgress] after each one. A
 * path skipped here is not counted, and the job skips it again without asking.
 */
@Throws(IOException::class)
internal fun FileJob.countFiles(
    sources: List<Path>,
    actionAllInfo: ActionAllInfo,
    onProgress: (ScanInfo) -> Unit
): ScanInfo {
    val scanInfo = ScanInfo()
    val visitor = object : SimpleFileVisitor<Path>() {
        @Throws(IOException::class)
        override fun preVisitDirectory(
            directory: Path,
            attributes: BasicFileAttributes
        ): FileVisitResult = count(attributes)

        @Throws(IOException::class)
        override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult =
            count(attributes)

        @Throws(InterruptedIOException::class)
        private fun count(attributes: BasicFileAttributes): FileVisitResult {
            scanInfo.incrementFileCount()
            scanInfo.addToSize(attributes.size())
            onProgress(scanInfo)
            throwIfInterrupted()
            return FileVisitResult.CONTINUE
        }
    }
    for (source in sources) {
        walkFileTreeAskingOnErrors(source, visitor, actionAllInfo, null)
    }
    return scanInfo
}

@Throws(IOException::class)
internal fun FileJob.scan(
    source: Path,
    @PluralsRes notificationTitleRes: Int,
    actionAllInfo: ActionAllInfo = ActionAllInfo()
): ScanInfo = scan(listOf(source), notificationTitleRes, actionAllInfo)

@Throws(IOException::class)
internal fun FileJob.scan(
    source: Path,
    recursive: Boolean,
    @PluralsRes notificationTitleRes: Int,
    actionAllInfo: ActionAllInfo = ActionAllInfo()
): ScanInfo {
    if (recursive) {
        return scan(source, notificationTitleRes, actionAllInfo)
    }
    val scanInfo = ScanInfo()
    val attributes = source.readAttributes(
        BasicFileAttributes::class.java,
        LinkOption.NOFOLLOW_LINKS
    )
    scanPath(attributes, scanInfo, notificationTitleRes)
    throwIfInterrupted()
    return scanInfo
}

private fun FileJob.scanPath(
    attributes: BasicFileAttributes,
    scanInfo: ScanInfo,
    @PluralsRes notificationTitleRes: Int
) {
    scanInfo.incrementFileCount()
    scanInfo.addToSize(attributes.size())
    postScanNotification(scanInfo, notificationTitleRes)
}

internal fun FileJob.getPrincipalName(principal: PosixPrincipal): String =
    principal.name ?: principal.id.toString()
