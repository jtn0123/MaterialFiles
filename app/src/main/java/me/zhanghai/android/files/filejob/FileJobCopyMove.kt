/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import androidx.annotation.AnyRes
import androidx.annotation.StringRes
import java.io.IOException
import java.io.InterruptedIOException
import java8.nio.file.CopyOption
import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.LinkOption
import java8.nio.file.Path
import java8.nio.file.StandardCopyOption
import kotlin.reflect.KMutableProperty0
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.loadFileItem
import me.zhanghai.android.files.provider.common.InvalidFileNameException
import me.zhanghai.android.files.provider.common.ProgressCopyOption
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
    this,
    source,
    type,
    useCopy,
    copyAttributes,
    transferInfo,
    actionAllInfo
).run(target)

/** What [copyOrMove] does after an attempt: finish, or try again with a new target or option. */
private sealed class CopyMoveStep {
    /** @param descend whether a directory source should be descended into */
    class Finish(val descend: Boolean) : CopyMoveStep()

    class Retry(val target: Path, val replaceExisting: Boolean) : CopyMoveStep()
}

/** One [copyOrMove] call, whose target and replace option change as conflicts are resolved. */
private class CopyMoveOperation(
    private val job: FileJob,
    private val source: Path,
    private val type: CopyMoveType,
    private val useCopy: Boolean,
    private val copyAttributes: Boolean,
    private val transferInfo: TransferInfo,
    private val actionAllInfo: ActionAllInfo
) {
    @Throws(IOException::class)
    fun run(target: Path): Boolean = when {
        // Don't allow copy/move into the source itself.
        target.parent.startsWith(source) -> refuse(
            actionAllInfo::skipCopyMoveIntoItself,
            type.getResourceId(
                R.string.file_job_cannot_copy_into_itself_title,
                R.string.file_job_cannot_extract_into_itself_title,
                R.string.file_job_cannot_move_into_itself_title
            ),
            R.string.file_job_cannot_copy_move_into_itself_message
        )

        // Don't allow copy/move over the source itself or its ancestors.
        source.startsWith(target) -> refuse(
            actionAllInfo::skipCopyMoveOverItself,
            type.getResourceId(
                R.string.file_job_cannot_copy_over_itself_title,
                R.string.file_job_cannot_extract_over_itself_title,
                R.string.file_job_cannot_move_over_itself_title
            ),
            R.string.file_job_cannot_copy_move_over_itself_message
        )

        else -> transfer(target)
    }

    /** Skips the source, after asking whether to go on at all unless told already for all. */
    @Throws(InterruptedIOException::class)
    private fun refuse(
        skipAll: KMutableProperty0<Boolean>,
        @StringRes titleRes: Int,
        @StringRes messageRes: Int
    ): Boolean {
        if (!skipAll.get()) {
            val result = job.showErrorDialog(
                job.getString(titleRes),
                job.getString(messageRes),
                null,
                true,
                job.getString(R.string.skip),
                job.getString(android.R.string.cancel),
                null
            )
            if (refusalDecision(result, skipAll) == ErrorDecision.CANCEL) {
                throw InterruptedIOException()
            }
        }
        return skip()
    }

    @Throws(IOException::class)
    private fun transfer(target: Path): Boolean {
        var retry = CopyMoveStep.Retry(target, false)
        while (true) {
            when (val step = attempt(retry.target, retry.replaceExisting)) {
                is CopyMoveStep.Finish -> return step.descend
                is CopyMoveStep.Retry -> retry = step
            }
        }
    }

    @Throws(IOException::class)
    private fun attempt(target: Path, replaceExisting: Boolean): CopyMoveStep {
        val options = createOptions(replaceExisting)
        return try {
            postNotification()
            if (useCopy) {
                source.copyTo(target, *options)
            } else {
                source.moveTo(target, *options)
            }
            transferInfo.incrementTransferredFileCount()
            postNotification()
            CopyMoveStep.Finish(true)
        } catch (e: FileAlreadyExistsException) {
            resolveConflict(target, replaceExisting, e)
        } catch (e: InterruptedIOException) {
            throw e
        } catch (e: IOException) {
            e.logWarning("FileJobCopyMove", "copyOrMove($source)")
            val decision = job.decideOnError(e, actionAllInfo::skipCopyMoveError) {
                job.showCopyMoveErrorDialog(source, target, type, e)
            }
            when (decision) {
                ErrorDecision.RETRY -> CopyMoveStep.Retry(target, replaceExisting)
                ErrorDecision.SKIP -> CopyMoveStep.Finish(skip())
                ErrorDecision.CANCEL -> throw InterruptedIOException()
            }
        }
    }

    private fun createOptions(replaceExisting: Boolean): Array<CopyOption> =
        mutableListOf<CopyOption>().apply {
            this += LinkOption.NOFOLLOW_LINKS
            if (copyAttributes) {
                this += StandardCopyOption.COPY_ATTRIBUTES
            }
            if (replaceExisting) {
                this += StandardCopyOption.REPLACE_EXISTING
            }
            this += ProgressCopyOption(PROGRESS_INTERVAL_MILLIS) {
                transferInfo.addToTransferredSize(it)
                postNotification()
            }
        }.toTypedArray()

    @Throws(IOException::class)
    private fun resolveConflict(
        target: Path,
        replaceExisting: Boolean,
        exception: FileAlreadyExistsException
    ): CopyMoveStep {
        val sourceFile = source.loadFileItem()
        val targetFile = target.loadFileItem()
        val sourceIsDirectory = sourceFile.attributesNoFollowLinks.isDirectory
        val targetIsDirectory = targetFile.attributesNoFollowLinks.isDirectory
        if (!sourceIsDirectory && targetIsDirectory) {
            // TODO: Don't allow replace directory with file.
            throw exception
        }
        val isMerge = sourceIsDirectory && targetIsDirectory
        val rememberedDecision = rememberedConflictDecision(isMerge, actionAllInfo)
        if (rememberedDecision != null) {
            return applyConflictDecision(
                rememberedDecision,
                target,
                targetFile,
                replaceExisting,
                null
            )
        }
        val result = job.showConflictDialog(sourceFile, targetFile, type)
        val decision = copyConflictDecision(result, isMerge, actionAllInfo)
        return applyConflictDecision(decision, target, targetFile, replaceExisting, result.name)
    }

    @Throws(InterruptedIOException::class)
    private fun applyConflictDecision(
        decision: CopyConflictDecision,
        target: Path,
        targetFile: FileItem,
        replaceExisting: Boolean,
        newName: String?
    ): CopyMoveStep = when (decision) {
        CopyConflictDecision.MERGE -> {
            transferInfo.addTransferredFile(targetFile.attributesNoFollowLinks.size())
            postNotification()
            CopyMoveStep.Finish(true)
        }

        CopyConflictDecision.REPLACE -> CopyMoveStep.Retry(target, true)

        CopyConflictDecision.RENAME ->
            CopyMoveStep.Retry(target.resolveSibling(newName), replaceExisting)

        CopyConflictDecision.SKIP -> CopyMoveStep.Finish(skip())

        CopyConflictDecision.CANCEL -> throw InterruptedIOException()
    }

    /** Leaves the source behind, which is never descended into, and returns false. */
    private fun skip(): Boolean {
        transferInfo.skipFile(source)
        postNotification()
        return false
    }

    private fun postNotification() {
        job.postCopyMoveNotification(transferInfo, source, type)
    }
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
