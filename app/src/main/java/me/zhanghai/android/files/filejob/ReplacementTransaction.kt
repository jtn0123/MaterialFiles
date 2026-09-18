package me.zhanghai.android.files.filejob

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.locks.ReentrantLock

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
    val lock = replacementLocks[(target.hashCode() and Int.MAX_VALUE) % replacementLocks.size]
    try {
        lock.lockInterruptibly()
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw InterruptedIOException("Save canceled while waiting for another writer").apply {
            initCause(e)
        }
    }
    try {
        replaceLocked(target, staged, backup, write, move, delete, atomicReplace)
    } finally {
        lock.unlock()
    }
}

private val replacementLocks = Array(64) { ReentrantLock() }

private fun <T> replaceLocked(
    target: T,
    staged: T,
    backup: T,
    write: (T) -> Unit,
    move: (T, T) -> Unit,
    delete: (T) -> Unit,
    atomicReplace: ((T, T) -> Unit)?
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
