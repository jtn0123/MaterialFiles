/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import android.widget.Toast
import java.io.IOException
import java.io.InterruptedIOException
import java8.nio.file.Path
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import me.zhanghai.android.files.app.BackgroundActivityStarter
import me.zhanghai.android.files.compat.mainExecutorCompat
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.provider.common.PosixFileStore
import me.zhanghai.android.files.provider.common.ReadOnlyFileSystemException
import me.zhanghai.android.files.provider.common.UserActionRequiredException
import me.zhanghai.android.files.provider.common.getFileStore
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.showToast

private fun FileJob.showToast(textRes: Int, duration: Int = Toast.LENGTH_SHORT) {
    service.mainExecutorCompat.execute {
        service.showToast(textRes, duration)
    }
}

internal fun FileJob.showToast(text: CharSequence, duration: Int = Toast.LENGTH_SHORT) {
    service.mainExecutorCompat.execute {
        service.showToast(text, duration)
    }
}

/**
 * Blocks until the user has answered, without keeping the device awake while they take their
 * time.
 */
@Throws(InterruptedException::class)
private fun <T> FileJob.waitingForUser(block: suspend CoroutineScope.() -> T): T {
    service.setJobWaitingForUser(this, true)
    try {
        return runBlocking(block = block)
    } finally {
        service.setJobWaitingForUser(this, false)
    }
}

// TODO: Make invalid file name, remount etc user actions as well.
@Throws(InterruptedIOException::class)
private fun FileJob.showAndroidUserAction(exception: UserActionRequiredException): Boolean = try {
    waitingForUser {
        suspendCoroutine { continuation ->
            val userAction = exception.getUserAction(continuation, service)
            BackgroundActivityStarter.startActivity(
                userAction.intent,
                userAction.title,
                userAction.message,
                service
            )
        }
    }
} catch (e: InterruptedException) {
    throw InterruptedIOException().apply { initCause(e) }
}

@Throws(InterruptedIOException::class)
private fun FileJob.showAndroidErrorDialog(
    title: CharSequence,
    message: CharSequence,
    readOnlyFileStore: PosixFileStore?,
    showAll: Boolean,
    positiveButtonText: CharSequence?,
    negativeButtonText: CharSequence?,
    neutralButtonText: CharSequence?
): ErrorResult = try {
    waitingForUser {
        suspendCoroutine { continuation ->
            BackgroundActivityStarter.startActivity(
                FileJobErrorDialogActivity::class.createIntent().putArgs(
                    FileJobErrorDialogFragment.Args(
                        title,
                        message,
                        readOnlyFileStore,
                        showAll,
                        positiveButtonText,
                        negativeButtonText,
                        neutralButtonText
                    ) { action, isAll ->
                        continuation.resume(ErrorResult(action, isAll))
                    }
                ),
                title,
                message,
                service
            )
        }
    }
} catch (e: InterruptedException) {
    throw InterruptedIOException().apply { initCause(e) }
}

internal fun FileJob.getReadOnlyFileStore(path: Path, exception: IOException): PosixFileStore? {
    if (exception !is ReadOnlyFileSystemException || !path.isLinuxPath) {
        return null
    }
    val fileStore = try {
        path.getFileStore() as PosixFileStore
    } catch (e: IOException) {
        e.printStackTrace()
        return null
    }
    return if (fileStore.isReadOnly) fileStore else null
}

internal class ErrorResult(val action: FileJobErrorAction, val isAll: Boolean)

@Throws(IOException::class)
private fun FileJob.showAndroidConflictDialog(
    sourceFile: FileItem,
    targetFile: FileItem,
    type: CopyMoveType
): ConflictResult = try {
    waitingForUser {
        suspendCoroutine { continuation ->
            BackgroundActivityStarter.startActivity(
                FileJobConflictDialogActivity::class.createIntent().putArgs(
                    FileJobConflictDialogFragment.Args(
                        sourceFile,
                        targetFile,
                        type
                    ) { action, name, all ->
                        continuation.resume(ConflictResult(action, name, all))
                    }
                ),
                FileJobConflictDialogFragment.getTitle(sourceFile, targetFile, service),
                FileJobConflictDialogFragment.getMessage(sourceFile, targetFile, type, service),
                service
            )
        }
    }
} catch (e: InterruptedException) {
    throw InterruptedIOException().apply { initCause(e) }
}

internal class ConflictResult(
    val action: FileJobConflictAction,
    val name: String?,
    val isAll: Boolean
)

internal class AndroidFileJobDecisions(private val job: FileJob) : FileJobDecisions {
    override fun error(request: FileJobErrorRequest): ErrorResult = with(request) {
        job.showAndroidErrorDialog(
            title,
            message,
            readOnlyFileStore,
            showAll,
            positiveButtonText,
            negativeButtonText,
            neutralButtonText
        )
    }
    override fun conflict(source: FileItem, target: FileItem, type: CopyMoveType): ConflictResult =
        job.showAndroidConflictDialog(source, target, type)
    override fun userAction(exception: UserActionRequiredException): Boolean =
        job.showAndroidUserAction(exception)
}

internal fun FileJob.showUserAction(exception: UserActionRequiredException): Boolean =
    decisions.userAction(exception)

internal fun FileJob.showConflictDialog(
    sourceFile: FileItem,
    targetFile: FileItem,
    type: CopyMoveType
): ConflictResult = decisions.conflict(sourceFile, targetFile, type)

internal fun FileJob.showErrorDialog(
    title: CharSequence,
    message: CharSequence,
    readOnlyFileStore: PosixFileStore?,
    showAll: Boolean,
    positiveButtonText: CharSequence?,
    negativeButtonText: CharSequence?,
    neutralButtonText: CharSequence?
): ErrorResult = decisions.error(
    FileJobErrorRequest(
        title,
        message,
        readOnlyFileStore,
        showAll,
        positiveButtonText,
        negativeButtonText,
        neutralButtonText
    )
)
