/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import androidx.annotation.PluralsRes
import java.io.IOException
import java8.nio.file.FileVisitResult
import java8.nio.file.Files
import java8.nio.file.Path
import java8.nio.file.SimpleFileVisitor
import java8.nio.file.attribute.BasicFileAttributes

/** Sets an attribute on one file, counting it in the [TransferInfo] of the job. */
internal typealias SetAttribute = (Path, BasicFileAttributes, TransferInfo, ActionAllInfo) -> Unit

/**
 * Scans [path] and then calls [setAttribute] on it, and on everything under it if [recursive], for
 * the jobs that set an attribute such as the mode or the owner.
 */
@Throws(IOException::class)
internal fun FileJob.walkSettingAttribute(
    path: Path,
    recursive: Boolean,
    @PluralsRes scanNotificationTitleRes: Int,
    setAttribute: SetAttribute
) {
    val actionAllInfo = ActionAllInfo()
    val scanInfo = scan(path, recursive, scanNotificationTitleRes, actionAllInfo)
    val transferInfo = TransferInfo(scanInfo, null)
    val decide = walkErrorDecider(actionAllInfo, transferInfo)
    walkSettingAttribute(path, recursive, decide) { file, attributes ->
        setAttribute(file, attributes, transferInfo, actionAllInfo)
    }
}

/**
 * Calls [visit] on [path], and on everything under it if [recursive], deciding on each failure of
 * the walk with [decide].
 */
@Throws(IOException::class)
internal fun FileJob.walkSettingAttribute(
    path: Path,
    recursive: Boolean,
    decide: WalkErrorDecider,
    visit: (Path, BasicFileAttributes) -> Unit
) {
    val visitor = SettingAttributeVisitor { file, attributes ->
        visit(file, attributes)
        throwIfInterrupted()
    }
    // A retry at the start path walks it the same way again; anything below it is walked plainly.
    val walk: FileTreeWalk = { start, walkVisitor ->
        if (start == path) {
            walkFileTreeForSettingAttributes(start, recursive, walkVisitor)
        } else {
            Files.walkFileTree(start, walkVisitor)
        }
    }
    walk(path, WalkErrorVisitor(visitor, decide, walk))
}

/**
 * Visits a directory before its children just like a file, since setting an attribute on either is
 * the same step. A file that cannot be visited or a directory that cannot be listed fails the walk,
 * unless the visitor is wrapped in a [WalkErrorVisitor] as [walkSettingAttribute] does.
 */
internal class SettingAttributeVisitor(private val visit: (Path, BasicFileAttributes) -> Unit) :
    SimpleFileVisitor<Path>() {
    @Throws(IOException::class)
    override fun preVisitDirectory(
        directory: Path,
        attributes: BasicFileAttributes
    ): FileVisitResult = visitFile(directory, attributes)

    @Throws(IOException::class)
    override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
        visit(file, attributes)
        return FileVisitResult.CONTINUE
    }
}
