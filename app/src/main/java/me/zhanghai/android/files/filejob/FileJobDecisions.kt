package me.zhanghai.android.files.filejob

import kotlin.reflect.KMutableProperty0
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.provider.common.PosixFileStore
import me.zhanghai.android.files.provider.common.UserActionRequiredException

internal data class FileJobErrorRequest(
    val title: CharSequence,
    val message: CharSequence,
    val readOnlyFileStore: PosixFileStore?,
    val showAll: Boolean,
    val positiveButtonText: CharSequence?,
    val negativeButtonText: CharSequence?,
    val neutralButtonText: CharSequence?
)

/** The engine asks for decisions; only the Android implementation opens activities. */
internal interface FileJobDecisions {
    fun error(request: FileJobErrorRequest): ErrorResult
    fun conflict(source: FileItem, target: FileItem, type: CopyMoveType): ConflictResult
    fun userAction(exception: UserActionRequiredException): Boolean
}

internal enum class ErrorDecision { RETRY, SKIP, CANCEL }

/**
 * Maps the answer to a retry, skip or cancel dialog, remembering a "skip all" in [skipAll] so that
 * later errors are skipped without asking.
 */
internal fun errorDecision(
    result: ErrorResult,
    skipAll: KMutableProperty0<Boolean>
): ErrorDecision = when (result.action) {
    FileJobErrorAction.POSITIVE -> ErrorDecision.RETRY

    FileJobErrorAction.NEGATIVE -> {
        if (result.isAll) skipAll.set(true)
        ErrorDecision.SKIP
    }

    FileJobErrorAction.CANCELED -> ErrorDecision.SKIP

    FileJobErrorAction.NEUTRAL -> ErrorDecision.CANCEL
}

/**
 * Maps the answer to a skip or cancel dialog for a copy or move that cannot be done at all,
 * remembering a "skip all" in [skipAll].
 */
internal fun refusalDecision(
    result: ErrorResult,
    skipAll: KMutableProperty0<Boolean>
): ErrorDecision = when (result.action) {
    FileJobErrorAction.POSITIVE -> {
        if (result.isAll) skipAll.set(true)
        ErrorDecision.SKIP
    }

    FileJobErrorAction.CANCELED -> ErrorDecision.SKIP

    FileJobErrorAction.NEGATIVE -> ErrorDecision.CANCEL

    FileJobErrorAction.NEUTRAL -> throw AssertionError(result.action)
}

/** Maps the answer to a retry or cancel dialog, for a step that cannot be skipped. */
internal fun retryOrCancelDecision(result: ErrorResult): ErrorDecision = when (result.action) {
    FileJobErrorAction.POSITIVE -> ErrorDecision.RETRY
    FileJobErrorAction.NEGATIVE, FileJobErrorAction.CANCELED -> ErrorDecision.CANCEL
    FileJobErrorAction.NEUTRAL -> throw AssertionError(result.action)
}

internal enum class CopyConflictDecision { MERGE, REPLACE, RENAME, SKIP, CANCEL }

internal fun copyConflictDecision(
    result: ConflictResult,
    isMerge: Boolean,
    all: ActionAllInfo
): CopyConflictDecision = when (result.action) {
    FileJobConflictAction.MERGE_OR_REPLACE -> {
        if (result.isAll) {
            if (isMerge) all.merge = true else all.replace = true
        }
        if (isMerge) CopyConflictDecision.MERGE else CopyConflictDecision.REPLACE
    }

    FileJobConflictAction.RENAME -> CopyConflictDecision.RENAME

    FileJobConflictAction.SKIP -> {
        if (result.isAll) {
            if (isMerge) all.skipMerge = true else all.skipReplace = true
        }
        CopyConflictDecision.SKIP
    }

    FileJobConflictAction.CANCELED -> CopyConflictDecision.SKIP

    FileJobConflictAction.CANCEL -> CopyConflictDecision.CANCEL
}

/**
 * Returns what an earlier "apply to all" answer decided for a conflict of this kind, or null if
 * the user has to be asked.
 */
internal fun rememberedConflictDecision(
    isMerge: Boolean,
    all: ActionAllInfo
): CopyConflictDecision? = if (isMerge) {
    when {
        all.merge -> CopyConflictDecision.MERGE
        all.skipMerge -> CopyConflictDecision.SKIP
        else -> null
    }
} else {
    when {
        all.replace -> CopyConflictDecision.REPLACE
        all.skipReplace -> CopyConflictDecision.SKIP
        else -> null
    }
}
