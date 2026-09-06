/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import java.io.IOException
import java8.nio.file.AtomicMoveNotSupportedException
import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.FileSystemException
import java8.nio.file.StandardCopyOption

/**
 * The copy and move algorithm every provider shares, written once.
 *
 * A provider supplies the primitive operations on its own path and attribute types; this class
 * supplies the order they run in and the guarantees the file jobs rely on:
 * - copying a file onto itself is a no-op that still reports its size as progress;
 * - an existing target is a [FileAlreadyExistsException] unless [CopyOptions.replaceExisting];
 * - a regular file that replaces another is written beside it (see [replacementSibling]) and
 *   renamed over it once complete, so a failed transfer never leaves the user with neither;
 * - a transfer that fails removes whatever it had written;
 * - a move tries a rename first and falls back to copy-then-delete; if the source then cannot
 *   be deleted, the copy is removed too so the move does not turn into a duplicate;
 * - copying attributes is best effort and never fails the operation.
 *
 * Every provider operation throws exceptions already mapped to the NIO types
 * ([java8.nio.file.NoSuchFileException], [FileAlreadyExistsException], ...), because the
 * algorithm reacts to them. [InterruptedIOException][java.io.InterruptedIOException] passes
 * through untouched so that cancellation keeps working.
 */
internal abstract class AbstractCopyMove<P : Any, A : Any> {
    protected enum class FileType { REGULAR_FILE, DIRECTORY, SYMBOLIC_LINK, OTHER }

    /** Attributes of [path], following links unless [noFollowLinks]. */
    @Throws(IOException::class)
    protected abstract fun readAttributes(path: P, noFollowLinks: Boolean): A

    /** Attributes of [path] itself (links not followed), or null when it does not exist. */
    @Throws(IOException::class)
    protected abstract fun readAttributesOrNull(path: P): A?

    protected abstract fun isSameFile(
        source: P,
        sourceAttributes: A,
        target: P,
        targetAttributes: A
    ): Boolean

    protected abstract fun getFileType(attributes: A): FileType

    protected abstract fun getSize(attributes: A): Long

    /**
     * Copies the content of a regular file to [target], which does not exist yet, reporting
     * progress through [copyOptions]. Whatever was written before a failure is left for
     * [delete].
     */
    @Throws(IOException::class)
    protected abstract fun copyRegularFile(
        source: P,
        sourceAttributes: A,
        target: P,
        copyOptions: CopyOptions
    )

    @Throws(IOException::class)
    protected abstract fun createDirectory(target: P, sourceAttributes: A, copyOptions: CopyOptions)

    /**
     * Recreates the link at [source] as [target]; throws [FileAlreadyExistsException] when the
     * target exists and [UnsupportedOperationException] when the provider has no links.
     */
    @Throws(IOException::class)
    protected abstract fun copySymbolicLink(
        source: P,
        sourceAttributes: A,
        target: P,
        copyOptions: CopyOptions
    )

    /** Deletes a file, an empty directory or a link; a path that does not exist is fine. */
    @Throws(IOException::class)
    protected abstract fun delete(path: P)

    /** A name beside [target] to write its replacement into. */
    protected abstract fun replacementSibling(target: P): P

    /**
     * Whether [rename] can be attempted for a move at all; false when source and target can
     * never be on the same provider.
     */
    protected open val canRename: Boolean
        get() = true

    /**
     * Renames [source] to [target]; with [replaceExisting], an existing target is replaced.
     * Throws [AtomicMoveNotSupportedException] when the rename cannot be done in one step and
     * any other mapped exception when it failed.
     */
    @Throws(IOException::class)
    protected abstract fun rename(source: P, target: P, replaceExisting: Boolean)

    /** Copies what [copyOptions] asks of the attributes; best effort, must not throw. */
    protected abstract fun copyAttributes(
        source: P,
        sourceAttributes: A,
        target: P,
        copyOptions: CopyOptions
    )

    @Throws(IOException::class)
    fun copy(source: P, target: P, copyOptions: CopyOptions) {
        if (copyOptions.atomicMove) {
            throw UnsupportedOperationException(StandardCopyOption.ATOMIC_MOVE.toString())
        }
        val sourceAttributes = readAttributes(source, copyOptions.noFollowLinks)
        val targetAttributes = readAttributesOrNull(target)
        if (targetAttributes != null) {
            if (isSameFile(source, sourceAttributes, target, targetAttributes)) {
                copyOptions.progressListener?.invoke(getSize(sourceAttributes))
                return
            }
            if (!copyOptions.replaceExisting) {
                throw FileAlreadyExistsException(source.toString(), target.toString(), null)
            }
        }
        when (getFileType(sourceAttributes)) {
            FileType.REGULAR_FILE ->
                copyRegularFileReplacing(
                    source,
                    sourceAttributes,
                    target,
                    targetAttributes != null,
                    copyOptions
                )

            FileType.DIRECTORY -> {
                if (targetAttributes != null) {
                    delete(target)
                }
                createDirectory(target, sourceAttributes, copyOptions)
                copyOptions.progressListener?.invoke(getSize(sourceAttributes))
            }

            FileType.SYMBOLIC_LINK -> {
                try {
                    copySymbolicLink(source, sourceAttributes, target, copyOptions)
                } catch (e: FileAlreadyExistsException) {
                    if (!copyOptions.replaceExisting) {
                        throw e
                    }
                    // Not deleted beforehand: the provider may not support links at all.
                    try {
                        delete(target)
                        copySymbolicLink(source, sourceAttributes, target, copyOptions)
                    } catch (e2: IOException) {
                        e2.addSuppressed(e)
                        throw e2
                    }
                }
                copyOptions.progressListener?.invoke(getSize(sourceAttributes))
            }

            FileType.OTHER ->
                throw FileSystemException(source.toString(), null, "Cannot copy a special file")
        }
        copyAttributes(source, sourceAttributes, target, copyOptions)
    }

    @Throws(IOException::class)
    private fun copyRegularFileReplacing(
        source: P,
        sourceAttributes: A,
        target: P,
        isReplacing: Boolean,
        copyOptions: CopyOptions
    ) {
        val writeTarget = if (isReplacing) replacementSibling(target) else target
        try {
            copyRegularFile(source, sourceAttributes, writeTarget, copyOptions)
        } catch (e: IOException) {
            deleteSuppressing(writeTarget, e)
            throw e
        } catch (e: UnsupportedOperationException) {
            deleteSuppressing(writeTarget, e)
            throw e
        }
        if (isReplacing) {
            try {
                rename(writeTarget, target, true)
            } catch (e: IOException) {
                deleteSuppressing(writeTarget, e)
                throw e
            } catch (e: UnsupportedOperationException) {
                deleteSuppressing(writeTarget, e)
                throw e
            }
        }
    }

    @Throws(IOException::class)
    fun move(source: P, target: P, copyOptions: CopyOptions) {
        val sourceAttributes = readAttributes(source, true)
        val targetAttributes = readAttributesOrNull(target)
        if (targetAttributes != null) {
            if (isSameFile(source, sourceAttributes, target, targetAttributes)) {
                copyOptions.progressListener?.invoke(getSize(sourceAttributes))
                return
            }
            if (!copyOptions.replaceExisting) {
                throw FileAlreadyExistsException(source.toString(), target.toString(), null)
            }
        }
        if (canRename) {
            val renamed = try {
                rename(source, target, targetAttributes != null)
                true
            } catch (e: IOException) {
                if (copyOptions.atomicMove) {
                    throw e
                }
                false
            } catch (e: UnsupportedOperationException) {
                if (copyOptions.atomicMove) {
                    throw AtomicMoveNotSupportedException(
                        source.toString(),
                        target.toString(),
                        e.message
                    ).apply { initCause(e) }
                }
                false
            }
            if (renamed) {
                copyOptions.progressListener?.invoke(getSize(sourceAttributes))
                return
            }
        }
        if (copyOptions.atomicMove) {
            throw AtomicMoveNotSupportedException(
                source.toString(),
                target.toString(),
                "Cannot move the file in one step"
            )
        }
        val copyOptionsForCopy = if (copyOptions.copyAttributes && copyOptions.noFollowLinks) {
            copyOptions
        } else {
            CopyOptions(
                copyOptions.replaceExisting,
                true,
                false,
                true,
                copyOptions.progressIntervalMillis,
                copyOptions.progressListener
            )
        }
        copy(source, target, copyOptionsForCopy)
        try {
            delete(source)
        } catch (e: IOException) {
            deleteSuppressing(target, e)
            throw e
        } catch (e: UnsupportedOperationException) {
            deleteSuppressing(target, e)
            throw e
        }
    }

    private fun deleteSuppressing(path: P, exception: Throwable) {
        try {
            delete(path)
        } catch (e: IOException) {
            exception.addSuppressed(e)
        } catch (e: UnsupportedOperationException) {
            exception.addSuppressed(e)
        }
    }
}
