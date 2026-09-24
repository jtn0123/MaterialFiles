/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import androidx.annotation.PluralsRes
import java.io.IOException
import java8.nio.file.FileVisitResult
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
    val scanInfo = scan(path, recursive, scanNotificationTitleRes)
    val transferInfo = TransferInfo(scanInfo, null)
    val actionAllInfo = ActionAllInfo()
    walkFileTreeForSettingAttributes(
        path,
        recursive,
        SettingAttributeVisitor { file, attributes ->
            setAttribute(file, attributes, transferInfo, actionAllInfo)
            throwIfInterrupted()
        }
    )
}

/**
 * Visits a directory before its children just like a file, since setting an attribute on either is
 * the same step.
 *
 * TODO: Prompt retry, skip, skip-all or abort when a file cannot be visited or a directory cannot
 *  be listed, which fails the job for now.
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
