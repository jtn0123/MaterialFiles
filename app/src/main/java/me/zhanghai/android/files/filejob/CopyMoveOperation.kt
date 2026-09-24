/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

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
import me.zhanghai.android.files.provider.common.ProgressCopyOption

/** What a [CopyMoveOperation] needs from the file system, the notification and the user. */
internal interface CopyMoveHost {
    /** Copies or moves the source to [target]. */
    @Throws(IOException::class)
    fun transfer(target: Path, options: Array<CopyOption>)

    @Throws(IOException::class)
    fun loadFileItem(path: Path): FileItem

    /** Takes the source, which is left behind, out of the files to transfer. */
    fun skipSource()

    fun postNotification()

    /** Asks whether to skip a source that cannot be copied or moved onto the target at all. */
    fun showRefusalDialog(@StringRes titleRes: Int, @StringRes messageRes: Int): ErrorResult

    /** Decides what to do about [exception] when transferring to [target]. */
    fun decideOnError(
        target: Path,
        exception: IOException,
        skipAll: KMutableProperty0<Boolean>
    ): ErrorDecision

    fun showConflictDialog(sourceFile: FileItem, targetFile: FileItem): ConflictResult
}

/** What [CopyMoveOperation] does after an attempt: finish, or try again with a new target or option. */
private sealed class CopyMoveStep {
    /** @param descend whether a directory source should be descended into */
    class Finish(val descend: Boolean) : CopyMoveStep()

    class Retry(val target: Path, val replaceExisting: Boolean) : CopyMoveStep()
}

/**
 * One copy or move of [source], whose target and replace option change as conflicts are resolved.
 *
 * @see copyOrMove
 */
internal class CopyMoveOperation(
    private val host: CopyMoveHost,
    private val source: Path,
    private val type: CopyMoveType,
    private val copyAttributes: Boolean,
    private val transferInfo: TransferInfo,
    private val actionAllInfo: ActionAllInfo
) {
    /** Returns whether a directory source should be descended into. */
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
            val result = host.showRefusalDialog(titleRes, messageRes)
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
            host.postNotification()
            host.transfer(target, options)
            transferInfo.incrementTransferredFileCount()
            host.postNotification()
            CopyMoveStep.Finish(true)
        } catch (e: FileAlreadyExistsException) {
            resolveConflict(target, replaceExisting, e)
        } catch (e: InterruptedIOException) {
            throw e
        } catch (e: IOException) {
            when (host.decideOnError(target, e, actionAllInfo::skipCopyMoveError)) {
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
                host.postNotification()
            }
        }.toTypedArray()

    @Throws(IOException::class)
    private fun resolveConflict(
        target: Path,
        replaceExisting: Boolean,
        exception: FileAlreadyExistsException
    ): CopyMoveStep {
        val sourceFile = host.loadFileItem(source)
        val targetFile = host.loadFileItem(target)
        val sourceIsDirectory = sourceFile.attributesNoFollowLinks.isDirectory
        val targetIsDirectory = targetFile.attributesNoFollowLinks.isDirectory
        if (!sourceIsDirectory && targetIsDirectory) {
            // A file never replaces a directory, so there is nothing to ask.
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
        val result = host.showConflictDialog(sourceFile, targetFile)
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
            host.postNotification()
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
        host.skipSource()
        host.postNotification()
        return false
    }
}
