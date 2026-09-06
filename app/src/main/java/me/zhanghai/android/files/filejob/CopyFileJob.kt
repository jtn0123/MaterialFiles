/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import java.io.IOException
import java8.nio.file.FileVisitResult
import java8.nio.file.Files
import java8.nio.file.LinkOption
import java8.nio.file.Path
import java8.nio.file.SimpleFileVisitor
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.archive.isArchivePath
import me.zhanghai.android.files.provider.common.ByteString
import me.zhanghai.android.files.provider.common.ByteStringBuilder
import me.zhanghai.android.files.provider.common.asByteStringListPath
import me.zhanghai.android.files.provider.common.exists
import me.zhanghai.android.files.provider.common.isDirectory
import me.zhanghai.android.files.provider.common.resolveForeign
import me.zhanghai.android.files.provider.common.toByteString
import me.zhanghai.android.files.util.asFileName

class CopyFileJob(private val sources: List<Path>, private val targetDirectory: Path) : FileJob() {
    @Throws(IOException::class)
    override fun run() {
        val isExtract = sources.all { it.isArchivePath }
        val scanInfo = scan(
            sources,
            if (isExtract) {
                R.plurals.file_job_extract_scan_notification_title_format
            } else {
                R.plurals.file_job_copy_scan_notification_title_format
            }
        )
        val transferInfo = TransferInfo(scanInfo, targetDirectory)
        val actionAllInfo = ActionAllInfo()
        for (source in sources) {
            val target = if (source.parent == targetDirectory) {
                getTargetPathForDuplicate(source)
            } else {
                targetDirectory.resolveForeign(getTargetFileName(source))
            }
            copyRecursively(source, target, isExtract, transferInfo, actionAllInfo)
            throwIfInterrupted()
        }
    }

    @Throws(IOException::class)
    private fun copyRecursively(
        source: Path,
        target: Path,
        isExtract: Boolean,
        transferInfo: TransferInfo,
        actionAllInfo: ActionAllInfo
    ) {
        Files.walkFileTree(
            source,
            object : SimpleFileVisitor<Path>() {
                @Throws(IOException::class)
                override fun preVisitDirectory(
                    directory: Path,
                    attributes: BasicFileAttributes
                ): FileVisitResult {
                    val directoryInTarget = target.resolveForeign(source.relativize(directory))
                    val copied = copy(
                        directory,
                        directoryInTarget,
                        isExtract,
                        transferInfo,
                        actionAllInfo
                    )
                    throwIfInterrupted()
                    return if (copied) FileVisitResult.CONTINUE else FileVisitResult.SKIP_SUBTREE
                }

                @Throws(IOException::class)
                override fun visitFile(
                    file: Path,
                    attributes: BasicFileAttributes
                ): FileVisitResult {
                    val fileInTarget = target.resolveForeign(source.relativize(file))
                    copy(file, fileInTarget, isExtract, transferInfo, actionAllInfo)
                    throwIfInterrupted()
                    return FileVisitResult.CONTINUE
                }

                @Throws(IOException::class)
                override fun visitFileFailed(file: Path, exception: IOException): FileVisitResult {
                    // TODO: Prompt retry, skip, skip-all or abort.
                    return super.visitFileFailed(file, exception)
                }
            }
        )
    }

    private fun getTargetPathForDuplicate(source: Path): Path {
        source.asByteStringListPath()
        val sourceFileName = source.fileNameByteString!!
        // We do want to follow symbolic links here.
        val countEndIndex = if (source.isDirectory()) {
            sourceFileName.length
        } else {
            sourceFileName.asFileName().baseName.length
        }
        val countInfo = getDuplicateCountInfo(sourceFileName, countEndIndex)
        var i = countInfo.count + 1
        while (i > 0) {
            val targetFileName = setDuplicateCount(sourceFileName, countInfo, i)
            val target = source.resolveSibling(targetFileName)
            if (!target.exists(LinkOption.NOFOLLOW_LINKS)) {
                return target
            }
            ++i
        }
        // Just leave it to conflict handling logic.
        return source
    }

    private fun getDuplicateCountInfo(fileName: ByteString, countEnd: Int): DuplicateCountInfo {
        while (true) {
            // /(?<=.) \(\d+\)$/
            var index = countEnd - 1
            // \)
            if (index < 0 || fileName[index] != ')'.code.toByte()) {
                break
            }
            --index
            // \d+
            val digitsEndInclusive = index
            while (index >= 0) {
                val b = fileName[index]
                if (b < '0'.code.toByte() || b > '9'.code.toByte()) {
                    break
                }
                --index
            }
            if (index == digitsEndInclusive) {
                break
            }
            val countString = fileName.substring(index + 1, digitsEndInclusive + 1).toString()
            val count = try {
                countString.toInt()
            } catch (e: NumberFormatException) {
                break
            }
            // \(
            if (index < 0 || fileName[index] != '('.code.toByte()) {
                break
            }
            --index
            //
            if (index < 0 || fileName[index] != ' '.code.toByte()) {
                break
            }
            // (?<=.)
            if (index == 0) {
                break
            }
            return DuplicateCountInfo(index, countEnd, count)
        }
        return DuplicateCountInfo(countEnd, countEnd, 0)
    }

    private fun setDuplicateCount(
        fileName: ByteString,
        countInfo: DuplicateCountInfo,
        count: Int
    ): ByteString = ByteStringBuilder(fileName.substring(0, countInfo.countStart))
        .append(" ($count)".toByteString())
        .append(fileName.substring(countInfo.countEnd))
        .toByteString()

    private class DuplicateCountInfo(val countStart: Int, val countEnd: Int, val count: Int)
}
