package me.zhanghai.android.files.filejob

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.locks.ReentrantLock
import java8.nio.file.Path

internal class ReplacementCommit<T>(
    val atomicReplace: ((T, T) -> Unit)? = null,
    val forceStaged: (T) -> Unit = {},
    val forceParent: (T) -> Unit = {}
)

/** Never overwrite the only good copy, even if a provider implements replace by deleting first. */
internal fun <T> replaceTransaction(
    target: T,
    staged: T,
    backup: T,
    write: (T) -> Unit,
    move: (T, T) -> Unit,
    delete: (T) -> Unit,
    commit: ReplacementCommit<T> = ReplacementCommit()
) {
    val identity = if (target is Path) target.toAbsolutePath().normalize() else target
    val lock = replacementLocks[(identity.hashCode() and Int.MAX_VALUE) % replacementLocks.size]
    try {
        lock.lockInterruptibly()
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw InterruptedIOException("Save canceled while waiting for another writer").apply {
            initCause(e)
        }
    }
    try {
        replaceLocked(target, staged, backup, write, move, delete, commit)
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
    commit: ReplacementCommit<T>
) {
    try {
        write(staged)
        commit.forceStaged(staged)
        if (commit.atomicReplace != null) {
            commit.atomicReplace.invoke(staged, target)
            commit.forceParent(target)
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
        // Keep the backup if directory synchronization fails after replacement.
        try {
            commit.forceParent(target)
        } catch (failure: IOException) {
            throw IOException(
                "Replacement is visible but durability failed. Your original is preserved at $backup",
                failure
            )
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
