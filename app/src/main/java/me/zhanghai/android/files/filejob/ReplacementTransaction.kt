package me.zhanghai.android.files.filejob

import java.io.IOException

/** Never overwrite the only good copy, even if a provider implements replace by deleting first. */
internal fun <T> replaceTransaction(
    target: T,
    staged: T,
    backup: T,
    write: (T) -> Unit,
    move: (T, T) -> Unit,
    delete: (T) -> Unit,
    atomicReplace: ((T, T) -> Unit)? = null
) {
    try {
        write(staged)
        if (atomicReplace != null) {
            atomicReplace(staged, target)
            return
        }
        move(target, backup)
        try {
            move(staged, target)
        } catch (failure: Exception) {
            val interrupted = Thread.interrupted()
            try {
                move(backup, target)
            } catch (rollback: Exception) {
                throw IOException(
                    "Save failed. Your original file is preserved at $backup"
                )
                    .apply {
                        addSuppressed(failure)
                        addSuppressed(rollback)
                    }
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
            throw failure
        }
        // A cleanup failure must not turn a completed save into a failed save.
        try {
            delete(backup)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    } finally {
        try {
            delete(staged)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
