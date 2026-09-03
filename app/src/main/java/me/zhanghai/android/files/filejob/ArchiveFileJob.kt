/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import java.io.IOException
import java.io.InterruptedIOException
import java8.nio.file.FileVisitResult
import java8.nio.file.Files
import java8.nio.file.Path
import java8.nio.file.SimpleFileVisitor
import java8.nio.file.StandardOpenOption
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.archive.archiver.ArchiveWriter
import me.zhanghai.android.files.provider.common.deleteIfExists
import me.zhanghai.android.files.provider.common.newByteChannel
import me.zhanghai.android.files.provider.common.resolveForeign
import me.zhanghai.android.files.util.toUserMessage

class ArchiveFileJob(
    private val sources: List<Path>,
    private val archiveFile: Path,
    private val format: Int,
    private val filter: Int,
    private val password: String?
) : FileJob() {
    @Throws(IOException::class)
    override fun run() {
        val scanInfo = scan(sources, R.plurals.file_job_archive_scan_notification_title_format)
        val channel = archiveFile.newByteChannel(
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
        )
        var successful = false
        try {
            channel.use {
                ArchiveWriter(channel, format, filter, password).use { writer ->
                    val transferInfo = TransferInfo(scanInfo, archiveFile)
                    for (source in sources) {
                        val target = getTargetFileName(source)
                        archiveRecursively(source, writer, target, transferInfo)
                        throwIfInterrupted()
                    }
                }
            }
            successful = true
        } finally {
            if (!successful) {
                try {
                    archiveFile.deleteIfExists()
                } catch (e: IOException) {
                    e.printStackTrace()
                } catch (e: UnsupportedOperationException) {
                    e.printStackTrace()
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun archiveRecursively(
        source: Path,
        writer: ArchiveWriter,
        target: Path,
        transferInfo: TransferInfo
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
                    archive(directory, writer, directoryInTarget, archiveFile, transferInfo)
                    throwIfInterrupted()
                    return FileVisitResult.CONTINUE
                }

                @Throws(IOException::class)
                override fun visitFile(
                    file: Path,
                    attributes: BasicFileAttributes
                ): FileVisitResult {
                    val fileInTarget = target.resolveForeign(source.relativize(file))
                    archive(file, writer, fileInTarget, archiveFile, transferInfo)
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
}

@Throws(IOException::class)
private fun FileJob.archive(
    file: Path,
    writer: ArchiveWriter,
    entryName: Path,
    archiveFile: Path,
    transferInfo: TransferInfo
) {
    try {
        postArchiveNotification(transferInfo, file)
        writer.write(file, entryName, PROGRESS_INTERVAL_MILLIS) {
            transferInfo.addToTransferredSize(it)
            postArchiveNotification(transferInfo, file)
        }
        transferInfo.incrementTransferredFileCount()
        postArchiveNotification(transferInfo, file)
    } catch (e: InterruptedIOException) {
        throw e
    } catch (e: IOException) {
        e.printStackTrace()
        val result = showErrorDialog(
            getString(R.string.file_job_archive_error_title_format, getFileName(file)),
            getString(
                R.string.file_job_archive_error_message_format,
                getFileName(archiveFile),
                e.toUserMessage(service)
            ),
            getReadOnlyFileStore(archiveFile, e),
            false,
            null,
            getString(android.R.string.cancel),
            null
        )
        when (result.action) {
            FileJobErrorAction.NEGATIVE, FileJobErrorAction.CANCELED ->
                throw InterruptedIOException()

            else -> throw AssertionError(result.action)
        }
    }
}

private fun FileJob.postArchiveNotification(transferInfo: TransferInfo, currentFile: Path) {
    postTransferSizeNotification(
        transferInfo,
        currentFile,
        R.string.file_job_archive_notification_title_one_format,
        R.plurals.file_job_archive_notification_title_multiple_format
    )
}
