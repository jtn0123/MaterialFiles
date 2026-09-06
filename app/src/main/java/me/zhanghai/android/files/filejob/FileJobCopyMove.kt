/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import androidx.annotation.AnyRes
import java.io.IOException
import java.io.InterruptedIOException
import java8.nio.file.CopyOption
import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.LinkOption
import java8.nio.file.Path
import java8.nio.file.StandardCopyOption
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.loadFileItem
import me.zhanghai.android.files.provider.common.InvalidFileNameException
import me.zhanghai.android.files.provider.common.ProgressCopyOption
import me.zhanghai.android.files.provider.common.UserActionRequiredException
import me.zhanghai.android.files.provider.common.copyTo
import me.zhanghai.android.files.provider.common.moveTo
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
): Boolean {
    val targetParent = target.parent
    if (targetParent.startsWith(source)) {
        // Don't allow copy/move into the source itself.
        if (actionAllInfo.skipCopyMoveIntoItself) {
            transferInfo.skipFile(source)
            postCopyMoveNotification(transferInfo, source, type)
            return false
        }
        val result = showErrorDialog(
            getString(
                type.getResourceId(
                    R.string.file_job_cannot_copy_into_itself_title,
                    R.string.file_job_cannot_extract_into_itself_title,
                    R.string.file_job_cannot_move_into_itself_title
                )
            ),
            getString(R.string.file_job_cannot_copy_move_into_itself_message),
            null,
            true,
            getString(R.string.skip),
            getString(android.R.string.cancel),
            null
        )
        return when (result.action) {
            FileJobErrorAction.POSITIVE -> {
                if (result.isAll) {
                    actionAllInfo.skipCopyMoveIntoItself = true
                }
                transferInfo.skipFile(source)
                postCopyMoveNotification(transferInfo, source, type)
                false
            }

            FileJobErrorAction.CANCELED -> {
                transferInfo.skipFile(source)
                postCopyMoveNotification(transferInfo, source, type)
                false
            }

            FileJobErrorAction.NEGATIVE -> throw InterruptedIOException()

            else -> throw AssertionError(result.action)
        }
    }
    if (source.startsWith(target)) {
        // Don't allow copy/move over the source itself or its ancestors.
        if (actionAllInfo.skipCopyMoveOverItself) {
            transferInfo.skipFile(source)
            postCopyMoveNotification(transferInfo, source, type)
            return false
        }
        val result = showErrorDialog(
            getString(
                type.getResourceId(
                    R.string.file_job_cannot_copy_over_itself_title,
                    R.string.file_job_cannot_extract_over_itself_title,
                    R.string.file_job_cannot_move_over_itself_title
                )
            ),
            getString(R.string.file_job_cannot_copy_move_over_itself_message),
            null,
            true,
            getString(R.string.skip),
            getString(android.R.string.cancel),
            null
        )
        return when (result.action) {
            FileJobErrorAction.POSITIVE -> {
                if (result.isAll) {
                    actionAllInfo.skipCopyMoveOverItself = true
                }
                transferInfo.skipFile(source)
                postCopyMoveNotification(transferInfo, source, type)
                false
            }

            FileJobErrorAction.CANCELED -> {
                transferInfo.skipFile(source)
                postCopyMoveNotification(transferInfo, source, type)
                false
            }

            FileJobErrorAction.NEGATIVE -> throw InterruptedIOException()

            else -> throw AssertionError(result.action)
        }
    }
    var target = target
    var replaceExisting = false
    var retry: Boolean
    do {
        retry = false
        val options = mutableListOf<CopyOption>().apply {
            this += LinkOption.NOFOLLOW_LINKS
            if (copyAttributes) {
                this += StandardCopyOption.COPY_ATTRIBUTES
            }
            if (replaceExisting) {
                this += StandardCopyOption.REPLACE_EXISTING
            }
            this += ProgressCopyOption(PROGRESS_INTERVAL_MILLIS) {
                transferInfo.addToTransferredSize(it)
                postCopyMoveNotification(transferInfo, source, type)
            }
        }.toTypedArray()
        try {
            postCopyMoveNotification(transferInfo, source, type)
            if (useCopy) {
                source.copyTo(target, *options)
            } else {
                source.moveTo(target, *options)
            }
            transferInfo.incrementTransferredFileCount()
            postCopyMoveNotification(transferInfo, source, type)
        } catch (e: FileAlreadyExistsException) {
            val sourceFile = source.loadFileItem()
            val targetFile = target.loadFileItem()
            val sourceIsDirectory = sourceFile.attributesNoFollowLinks.isDirectory
            val targetIsDirectory = targetFile.attributesNoFollowLinks.isDirectory
            if (!sourceIsDirectory && targetIsDirectory) {
                // TODO: Don't allow replace directory with file.
                throw e
            }
            val isMerge = sourceIsDirectory && targetIsDirectory
            if (isMerge && actionAllInfo.merge) {
                transferInfo.addTransferredFile(targetFile.attributesNoFollowLinks.size())
                postCopyMoveNotification(transferInfo, source, type)
                return true
            } else if (!isMerge && actionAllInfo.replace) {
                replaceExisting = true
                retry = true
                continue
            } else if ((isMerge && actionAllInfo.skipMerge) ||
                (!isMerge && actionAllInfo.skipReplace)
            ) {
                transferInfo.skipFile(source)
                postCopyMoveNotification(transferInfo, source, type)
                return false
            }
            val result = showConflictDialog(sourceFile, targetFile, type)
            return when (result.action) {
                FileJobConflictAction.MERGE_OR_REPLACE -> {
                    if (result.isAll) {
                        if (isMerge) {
                            actionAllInfo.merge = true
                        } else {
                            actionAllInfo.replace = true
                        }
                    }
                    if (isMerge) {
                        transferInfo.addTransferredFile(targetFile.attributesNoFollowLinks.size())
                        postCopyMoveNotification(transferInfo, source, type)
                        true
                    } else {
                        replaceExisting = true
                        retry = true
                        continue
                    }
                }

                FileJobConflictAction.RENAME -> {
                    target = target.resolveSibling(result.name)
                    retry = true
                    continue
                }

                FileJobConflictAction.SKIP -> {
                    if (result.isAll) {
                        if (isMerge) {
                            actionAllInfo.skipMerge = true
                        } else {
                            actionAllInfo.skipReplace = true
                        }
                    }
                    transferInfo.skipFile(source)
                    postCopyMoveNotification(transferInfo, source, type)
                    false
                }

                FileJobConflictAction.CANCELED -> {
                    transferInfo.skipFile(source)
                    postCopyMoveNotification(transferInfo, source, type)
                    false
                }

                FileJobConflictAction.CANCEL -> throw InterruptedIOException()
            }
        } catch (e: InvalidFileNameException) {
            // TODO: Prompt invalid name.
            if (false) {
                retry = true
                continue
            }
            throw e
        } catch (e: InterruptedIOException) {
            throw e
        } catch (e: IOException) {
            e.printStackTrace()
            if (actionAllInfo.skipCopyMoveError) {
                recordSkippedError()
                transferInfo.skipFile(source)
                postCopyMoveNotification(transferInfo, source, type)
                return false
            }
            if (e is UserActionRequiredException) {
                val result = showUserAction(e)
                if (result) {
                    retry = true
                    continue
                }
            }
            val result = showErrorDialog(
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
                    getFileName(targetParent),
                    e.toUserMessage(service)
                ),
                getReadOnlyFileStore(target, e),
                true,
                getString(R.string.retry),
                getString(R.string.skip),
                getString(android.R.string.cancel)
            )
            return when (result.action) {
                FileJobErrorAction.POSITIVE -> {
                    retry = true
                    continue
                }

                FileJobErrorAction.NEGATIVE -> {
                    recordSkippedError()
                    if (result.isAll) {
                        actionAllInfo.skipCopyMoveError = true
                    }
                    transferInfo.skipFile(source)
                    postCopyMoveNotification(transferInfo, source, type)
                    false
                }

                FileJobErrorAction.CANCELED -> {
                    transferInfo.skipFile(source)
                    postCopyMoveNotification(transferInfo, source, type)
                    false
                }

                FileJobErrorAction.NEUTRAL -> throw InterruptedIOException()
            }
        }
    } while (retry)
    return true
}

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
