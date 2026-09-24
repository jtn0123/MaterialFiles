/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import androidx.annotation.AnyRes
import java.io.IOException
import java8.nio.file.CopyOption
import java8.nio.file.LinkOption
import java8.nio.file.Path
import java8.nio.file.StandardCopyOption
import kotlin.reflect.KMutableProperty0
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.loadFileItem
import me.zhanghai.android.files.provider.common.InvalidFileNameException
import me.zhanghai.android.files.provider.common.copyTo
import me.zhanghai.android.files.provider.common.moveTo
import me.zhanghai.android.files.util.logWarning
import me.zhanghai.android.files.util.toUserMessage

enum class CopyMoveType {
    COPY,
    EXTRACT,
    MOVE
}

fun CopyMoveType.getResourceId(
    @AnyRes copyRes: Int,
    @AnyRes extractRes: Int,
    @AnyRes moveRes: Int
): Int = when (this) {
    CopyMoveType.COPY -> copyRes
    CopyMoveType.EXTRACT -> extractRes
    CopyMoveType.MOVE -> moveRes
}

internal class ActionAllInfo(
    var skipCopyMoveIntoItself: Boolean = false,
    var skipCopyMoveOverItself: Boolean = false,
    var merge: Boolean = false,
    var replace: Boolean = false,
    var skipMerge: Boolean = false,
    var skipReplace: Boolean = false,
    var skipCopyMoveError: Boolean = false,
    var skipDeleteError: Boolean = false,
    var skipRestoreSeLinuxContextError: Boolean = false,
    var skipSetGroupError: Boolean = false,
    var skipSetOwnerError: Boolean = false,
    var skipSetModeError: Boolean = false,
    var skipSetSeLinuxContextError: Boolean = false
)

@Throws(IOException::class)
internal fun FileJob.copy(
    source: Path,
    target: Path,
    isExtract: Boolean,
    transferInfo: TransferInfo,
    actionAllInfo: ActionAllInfo
): Boolean = copyOrMove(
    source,
    target,
    if (isExtract) CopyMoveType.EXTRACT else CopyMoveType.COPY,
    true,
    false,
    transferInfo,
    actionAllInfo
)

@Throws(IOException::class)
internal fun FileJob.moveAtomically(source: Path, target: Path) {
    source.moveTo(target, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.ATOMIC_MOVE)
}

/**
 * Copies or moves one file, resolving conflicts and errors with the user, and returns whether a
 * directory source should be descended into (true after a merge; false after a skip or when the
 * target was created as a whole).
 *
 * The decision flow, modelled on Nautilus' `copy_move_file`:
 * 1. Copying into or over the source itself is refused with a skip/cancel dialog.
 * 2. The transfer is attempted. `FileAlreadyExistsException` opens the conflict dialog:
 *    replace (retry with `REPLACE_EXISTING`), merge (directory onto directory; caller recurses),
 *    rename (retry with the new name), skip, or cancel.
 * 3. `UserActionRequiredException` (for example a read-only mount) runs its action and retries.
 * 4. Any other `IOException` opens the error dialog: retry, skip (recorded for the job's final
 *    toast), or cancel.
 *
 * Every "apply to all" choice is remembered in [actionAllInfo] so later files take the same
 * branch without a dialog. Cancel is delivered as `InterruptedIOException`, which ends the job
 * silently. [transferInfo] is kept current for the progress notification on every branch.
 *
 * @see <a href="https://github.com/GNOME/nautilus/blob/master/src/nautilus-file-operations.c">nautilus-file-operations.c</a>
 */
@Throws(IOException::class)
internal fun FileJob.copyOrMove(
    source: Path,
    target: Path,
    type: CopyMoveType,
    useCopy: Boolean,
    copyAttributes: Boolean,
    transferInfo: TransferInfo,
    actionAllInfo: ActionAllInfo
): Boolean = CopyMoveOperation(
    FileJobCopyMoveHost(this, source, type, useCopy, transferInfo),
    source,
    type,
    copyAttributes,
    transferInfo,
    actionAllInfo
).run(target)

/** Copies or moves [source] for real, and asks the user through [job]. */
private class FileJobCopyMoveHost(
    private val job: FileJob,
    private val source: Path,
    private val type: CopyMoveType,
    private val useCopy: Boolean,
    private val transferInfo: TransferInfo
) : CopyMoveHost {
    override fun transfer(target: Path, options: Array<CopyOption>) {
        if (useCopy) {
            source.copyTo(target, *options)
        } else {
            source.moveTo(target, *options)
        }
    }

    override fun loadFileItem(path: Path): FileItem = path.loadFileItem()

    override fun skipSource() {
        transferInfo.skipFile(source)
    }

    override fun postNotification() {
        job.postCopyMoveNotification(transferInfo, source, type)
    }

    override fun showRefusalDialog(titleRes: Int, messageRes: Int): ErrorResult =
        job.showErrorDialog(
            job.getString(titleRes),
            job.getString(messageRes),
            null,
            true,
            job.getString(R.string.skip),
            job.getString(android.R.string.cancel),
            null
        )

    override fun decideOnError(
        target: Path,
        exception: IOException,
        skipAll: KMutableProperty0<Boolean>
    ): ErrorDecision {
        exception.logWarning("FileJobCopyMove", "copyOrMove($source)")
        return job.decideOnError(exception, skipAll) {
            job.showCopyMoveErrorDialog(source, target, type, exception)
        }
    }

    override fun showConflictDialog(sourceFile: FileItem, targetFile: FileItem): ConflictResult =
        job.showConflictDialog(sourceFile, targetFile, type)
}

private fun FileJob.showCopyMoveErrorDialog(
    source: Path,
    target: Path,
    type: CopyMoveType,
    exception: IOException
): ErrorResult = showErrorDialog(
    getString(
        type.getResourceId(
            R.string.file_job_copy_error_title_format,
            R.string.file_job_extract_error_title_format,
            R.string.file_job_move_error_title_format
        ),
        getFileName(source)
    ),
    getString(
        type.getResourceId(
            R.string.file_job_copy_error_message_format,
            R.string.file_job_extract_error_message_format,
            R.string.file_job_move_error_message_format
        ),
        getFileName(target.parent),
        exception.toUserMessage(service)
    ),
    getReadOnlyFileStore(target, exception),
    true,
    // The same name fails again, so an invalid name offers skip and cancel only.
    if (exception is InvalidFileNameException) null else getString(R.string.retry),
    getString(R.string.skip),
    getString(android.R.string.cancel)
)

private fun FileJob.postCopyMoveNotification(
    transferInfo: TransferInfo,
    currentSource: Path,
    type: CopyMoveType
) {
    postTransferSizeNotification(
        transferInfo,
        currentSource,
        type.getResourceId(
            R.string.file_job_copy_notification_title_one_format,
            R.string.file_job_extract_notification_title_one_format,
            R.string.file_job_move_notification_title_one_format
        ),
        type.getResourceId(
            R.plurals.file_job_copy_notification_title_multiple_format,
            R.plurals.file_job_extract_notification_title_multiple_format,
            R.plurals.file_job_move_notification_title_multiple_format
        )
    )
}
