/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import android.content.Context
import java.io.FileNotFoundException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java8.nio.file.AccessDeniedException
import java8.nio.file.DirectoryNotEmptyException
import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.FileSystemException
import java8.nio.file.NoSuchFileException
import java8.nio.file.NotDirectoryException
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.common.InvalidFileNameException
import me.zhanghai.android.files.provider.common.IsDirectoryException
import me.zhanghai.android.files.provider.common.ReadOnlyFileSystemException

/**
 * A message for showing this throwable to the user, localized for the common file system errors.
 *
 * The raw [toString] stays the right thing for logs; this is only for toasts and error views.
 */
fun Throwable.toUserMessage(context: Context): String {
    val (stringRes, detail) = toUserMessageParts()
    return if (stringRes != null) context.getString(stringRes).withDetail(detail) else detail!!
}

/**
 * The string resource describing this throwable, if it is a recognized kind, and the detail to
 * show under it (the file and reason for file system errors, otherwise the most specific message).
 * Without a resource the detail is never null.
 */
internal fun Throwable.toUserMessageParts(): Pair<Int?, String?> {
    val fileSystemException = findCauseByClass<FileSystemException>()
    if (fileSystemException != null) {
        return fileSystemException.toFileSystemUserMessageParts()
    }
    val stringRes = when {
        findCauseByClass<FileNotFoundException>() != null -> R.string.error_file_not_found
        findCauseByClass<UnknownHostException>() != null -> R.string.error_unknown_host
        findCauseByClass<SocketTimeoutException>() != null -> R.string.error_connection_timed_out
        findCauseByClass<ConnectException>() != null -> R.string.error_connection_failed
        findCauseByClass<InterruptedIOException>() != null -> R.string.error_interrupted
        else -> null
    }
    return if (stringRes != null) {
        stringRes to rootMessage
    } else {
        null to (rootMessage ?: javaClass.simpleName)
    }
}

private fun FileSystemException.toFileSystemUserMessageParts(): Pair<Int?, String?> {
    val stringRes = when (this) {
        is AccessDeniedException -> R.string.error_access_denied
        is NoSuchFileException -> R.string.error_file_not_found
        is FileAlreadyExistsException -> R.string.error_file_already_exists
        is DirectoryNotEmptyException -> R.string.error_directory_not_empty
        is NotDirectoryException -> R.string.error_not_a_directory
        is IsDirectoryException -> R.string.error_is_a_directory
        is ReadOnlyFileSystemException -> R.string.error_read_only_file_system
        is InvalidFileNameException -> R.string.error_invalid_file_name
        else -> null
    }
    val detail = listOfNotNull(file, reason?.takeIf { it.isNotBlank() }).joinToString(": ")
    return if (stringRes != null) {
        stringRes to detail.ifEmpty { null }
    } else {
        null to detail.ifEmpty { message ?: javaClass.simpleName }
    }
}

/** The message of the deepest cause that has one; it is usually the most specific. */
private val Throwable.rootMessage: String?
    get() {
        var current: Throwable? = this
        var message: String? = null
        while (current != null) {
            current.message?.takeIf { it.isNotBlank() }?.let { message = it }
            current = current.cause
        }
        return message
    }

private fun String.withDetail(detail: String?): String =
    if (detail.isNullOrBlank()) this else "$this\n$detail"
