package me.zhanghai.android.files.filejob

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

internal enum class CopyErrorDecision { RETRY, SKIP, CANCEL }

internal fun copyErrorDecision(result: ErrorResult, all: ActionAllInfo): CopyErrorDecision =
    when (result.action) {
        FileJobErrorAction.POSITIVE -> CopyErrorDecision.RETRY

        FileJobErrorAction.NEGATIVE -> {
            if (result.isAll) all.skipCopyMoveError = true
            CopyErrorDecision.SKIP
        }

        FileJobErrorAction.CANCELED -> CopyErrorDecision.SKIP

        FileJobErrorAction.NEUTRAL -> CopyErrorDecision.CANCEL
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
