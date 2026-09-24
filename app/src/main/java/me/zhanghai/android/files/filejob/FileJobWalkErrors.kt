/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import java.io.IOException
import java.io.InterruptedIOException
import java8.nio.file.DirectoryIteratorException
import java8.nio.file.FileVisitResult
import java8.nio.file.FileVisitor
import java8.nio.file.Files
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.common.newDirectoryStream
import me.zhanghai.android.files.util.toUserMessage

/** Where a walk of a file tree failed. */
internal enum class WalkFailure {
    /** A file or directory could not be read, or a directory could not be opened for listing. */
    VISIT,

    /** A directory stopped listing its children part way through. */
    LIST
}

/** Decides what to do about a failure of a walk at a path. */
internal typealias WalkErrorDecider = (Path, IOException, WalkFailure) -> ErrorDecision

/** Walks the file tree at a path with a visitor, as [Files.walkFileTree] does. */
internal typealias FileTreeWalk = (Path, FileVisitor<in Path>) -> Unit

/**
 * Wraps [visitor] so that a walk carries on past a file that cannot be read or a directory that
 * cannot be listed, as [decide] says: a retry walks the path again (for a listing, only the
 * children not visited yet), a skip leaves it out and a cancel throws [InterruptedIOException].
 *
 * [visitor] never sees a failure: its `postVisitDirectory` is only called, with no exception, for
 * a directory whose whole subtree was walked. A directory with anything skipped below it is left
 * out of `postVisitDirectory`, so that a delete or a move does not try to remove it.
 */
internal class WalkErrorVisitor(
    private val visitor: FileVisitor<in Path>,
    private val decide: WalkErrorDecider,
    private val walk: FileTreeWalk = { path, visitor -> Files.walkFileTree(path, visitor) }
) : FileVisitor<Path> {
    private class Directory {
        val visitedChildren = mutableSetOf<Path>()
        var isIncomplete = false
    }

    private val directories = ArrayDeque<Directory>()

    @Throws(IOException::class)
    override fun preVisitDirectory(
        directory: Path,
        attributes: BasicFileAttributes
    ): FileVisitResult {
        markVisited(directory)
        val result = visitor.preVisitDirectory(directory, attributes)
        if (result == FileVisitResult.CONTINUE) {
            directories.addLast(Directory())
        }
        return result
    }

    @Throws(IOException::class)
    override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
        markVisited(file)
        return visitor.visitFile(file, attributes)
    }

    @Throws(IOException::class)
    override fun visitFileFailed(file: Path, exception: IOException): FileVisitResult {
        markVisited(file)
        if (exception is InterruptedIOException) {
            throw exception
        }
        when (decide(file, exception, WalkFailure.VISIT)) {
            ErrorDecision.RETRY -> walk(file, this)
            ErrorDecision.SKIP -> directories.lastOrNull()?.isIncomplete = true
            ErrorDecision.CANCEL -> throw InterruptedIOException()
        }
        return FileVisitResult.CONTINUE
    }

    @Throws(IOException::class)
    override fun postVisitDirectory(directory: Path, exception: IOException?): FileVisitResult {
        val frame = directories.last()
        var failure = exception
        while (failure != null) {
            if (failure is InterruptedIOException) {
                throw failure
            }
            failure = when (decide(directory, failure, WalkFailure.LIST)) {
                ErrorDecision.RETRY -> walkUnvisitedChildren(directory, frame)

                ErrorDecision.SKIP -> {
                    frame.isIncomplete = true
                    null
                }

                ErrorDecision.CANCEL -> throw InterruptedIOException()
            }
        }
        directories.removeLast()
        if (frame.isIncomplete) {
            directories.lastOrNull()?.isIncomplete = true
            return FileVisitResult.CONTINUE
        }
        return visitor.postVisitDirectory(directory, null)
    }

    private fun markVisited(path: Path) {
        directories.lastOrNull()?.visitedChildren?.add(path)
    }

    /** Lists [directory] again and walks what was not visited yet; returns a listing failure. */
    @Throws(IOException::class)
    private fun walkUnvisitedChildren(directory: Path, frame: Directory): IOException? {
        val stream = try {
            directory.newDirectoryStream()
        } catch (e: IOException) {
            return e
        }
        stream.use {
            val iterator = stream.iterator()
            while (true) {
                val child = try {
                    if (!iterator.hasNext()) {
                        break
                    }
                    iterator.next()
                } catch (e: DirectoryIteratorException) {
                    return e.cause
                }
                if (child !in frame.visitedChildren) {
                    walk(child, this)
                }
            }
        }
        return null
    }
}

/**
 * Decides on a failure of a job's walk at [path], asking with [showDialog] to retry, skip or
 * cancel. A "skip all" is remembered in [actionAllInfo] and so is every skipped path, so that the
 * walk after the scan does not ask again about a path the user already skipped. A skipped file is
 * left out of [transferInfo], unless the scan never counted it.
 */
internal fun FileJob.decideOnWalkError(
    path: Path,
    exception: IOException,
    failure: WalkFailure,
    actionAllInfo: ActionAllInfo,
    transferInfo: TransferInfo?,
    showDialog: () -> ErrorResult
): ErrorDecision {
    if (path in actionAllInfo.skippedWalkPaths) {
        return ErrorDecision.SKIP
    }
    val decision = decideOnError(exception, actionAllInfo::skipWalkError, showDialog)
    if (decision == ErrorDecision.SKIP) {
        actionAllInfo.skippedWalkPaths.add(path)
        if (failure == WalkFailure.VISIT) {
            transferInfo?.skipFileIgnoringSize()
        }
    }
    return decision
}

private fun FileJob.showWalkErrorDialog(
    path: Path,
    exception: IOException,
    failure: WalkFailure
): ErrorResult {
    val messageRes = when (failure) {
        WalkFailure.VISIT -> R.string.file_job_read_error_message_format
        WalkFailure.LIST -> R.string.file_job_list_error_message_format
    }
    return showRetrySkipCancelDialog(
        getString(R.string.file_job_read_error_title),
        getString(messageRes, getFileName(path), exception.toUserMessage(service)),
        path,
        exception
    )
}

/** Wraps [visitor] for a job, asking the user about each failure of the walk. */
internal fun FileJob.walkErrorVisitor(
    visitor: FileVisitor<in Path>,
    actionAllInfo: ActionAllInfo,
    transferInfo: TransferInfo?,
    walk: FileTreeWalk = { path, walkVisitor -> Files.walkFileTree(path, walkVisitor) }
): WalkErrorVisitor = WalkErrorVisitor(
    visitor,
    { path, exception, failure ->
        decideOnWalkError(path, exception, failure, actionAllInfo, transferInfo) {
            showWalkErrorDialog(path, exception, failure)
        }
    },
    walk
)

/** Walks [start] with [visitor], asking the user about each failure of the walk. */
@Throws(IOException::class)
internal fun FileJob.walkFileTreeAskingOnErrors(
    start: Path,
    visitor: FileVisitor<in Path>,
    actionAllInfo: ActionAllInfo,
    transferInfo: TransferInfo?
) {
    Files.walkFileTree(start, walkErrorVisitor(visitor, actionAllInfo, transferInfo))
}
