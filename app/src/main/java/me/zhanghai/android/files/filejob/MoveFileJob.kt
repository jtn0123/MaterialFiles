/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import java.io.IOException
import java.io.InterruptedIOException
import java8.nio.file.FileVisitResult
import java8.nio.file.Path
import java8.nio.file.SimpleFileVisitor
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.common.resolveForeign
import me.zhanghai.android.files.util.logWarning

class MoveFileJob(private val sources: List<Path>, private val targetDirectory: Path) : FileJob() {
    @Throws(IOException::class)
    override fun run() {
        val sourcesToMove = mutableListOf<Path>()
        for (source in sources) {
            val target = targetDirectory.resolveForeign(source.fileName)
            try {
                moveAtomically(source, target)
            } catch (e: InterruptedIOException) {
                throw e
            } catch (e: IOException) {
                sourcesToMove.add(source)
            }
            throwIfInterrupted()
        }
        val actionAllInfo = ActionAllInfo()
        val scanInfo = scan(
            sourcesToMove,
            R.plurals.file_job_move_scan_notification_title_format,
            actionAllInfo
        )
        val transferInfo = TransferInfo(scanInfo, targetDirectory)
        for (source in sourcesToMove) {
            val target = targetDirectory.resolveForeign(source.fileName)
            moveRecursively(source, target, transferInfo, actionAllInfo)
            throwIfInterrupted()
        }
    }

    @Throws(IOException::class)
    private fun moveRecursively(
        source: Path,
        target: Path,
        transferInfo: TransferInfo,
        actionAllInfo: ActionAllInfo
    ) {
        walkFileTreeAskingOnErrors(
            source,
            object : SimpleFileVisitor<Path>() {
                @Throws(IOException::class)
                override fun preVisitDirectory(
                    directory: Path,
                    attributes: BasicFileAttributes
                ): FileVisitResult {
                    val directoryInTarget = target.resolveForeign(source.relativize(directory))
                    try {
                        moveAtomically(directory, directoryInTarget)
                        throwIfInterrupted()
                        return FileVisitResult.SKIP_SUBTREE
                    } catch (e: InterruptedIOException) {
                        throw e
                    } catch (e: IOException) {
                        e.logWarning("MoveFileJob", "preVisitDirectory($directory)")
                    }
                    val copied =
                        copyForMove(directory, directoryInTarget, transferInfo, actionAllInfo)
                    throwIfInterrupted()
                    return if (copied) FileVisitResult.CONTINUE else FileVisitResult.SKIP_SUBTREE
                }

                @Throws(IOException::class)
                override fun visitFile(
                    file: Path,
                    attributes: BasicFileAttributes
                ): FileVisitResult {
                    val fileInTarget = target.resolveForeign(source.relativize(file))
                    try {
                        moveAtomically(file, fileInTarget)
                        throwIfInterrupted()
                        return FileVisitResult.CONTINUE
                    } catch (e: InterruptedIOException) {
                        throw e
                    } catch (e: IOException) {
                        e.logWarning("MoveFileJob", "visitFile($file)")
                    }
                    moveByCopy(file, fileInTarget, transferInfo, actionAllInfo)
                    throwIfInterrupted()
                    return FileVisitResult.CONTINUE
                }

                @Throws(IOException::class)
                override fun postVisitDirectory(
                    directory: Path,
                    exception: IOException?
                ): FileVisitResult {
                    // Only reached with no exception for a directory that was fully moved.
                    delete(directory, null, actionAllInfo)
                    throwIfInterrupted()
                    return FileVisitResult.CONTINUE
                }
            },
            actionAllInfo,
            transferInfo
        )
    }
}

@Throws(IOException::class)
private fun FileJob.copyForMove(
    source: Path,
    target: Path,
    transferInfo: TransferInfo,
    actionAllInfo: ActionAllInfo
): Boolean = copyOrMove(source, target, CopyMoveType.MOVE, true, true, transferInfo, actionAllInfo)

@Throws(IOException::class)
private fun FileJob.moveByCopy(
    source: Path,
    target: Path,
    transferInfo: TransferInfo,
    actionAllInfo: ActionAllInfo
): Boolean = copyOrMove(source, target, CopyMoveType.MOVE, false, true, transferInfo, actionAllInfo)
