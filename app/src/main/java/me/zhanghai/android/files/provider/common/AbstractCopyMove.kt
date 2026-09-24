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

    /**
     * [delete] for a provider that needs to know what it is deleting (WebDAV addresses a
     * collection with a trailing slash); the default ignores the type.
     */
    @Throws(IOException::class)
    protected open fun delete(path: P, fileType: FileType) {
        delete(path)
    }

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

    /** [rename] with the type of [source] known; the default ignores the type. */
    @Throws(IOException::class)
    protected open fun rename(source: P, target: P, fileType: FileType, replaceExisting: Boolean) {
        rename(source, target, replaceExisting)
    }

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
        if (targetAttributes != null &&
            isDone(source, sourceAttributes, target, targetAttributes, copyOptions)
        ) {
            return
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
                    delete(target, getFileType(targetAttributes))
                }
                createDirectory(target, sourceAttributes, copyOptions)
                copyOptions.progressListener?.invoke(getSize(sourceAttributes))
            }

            FileType.SYMBOLIC_LINK -> {
                copySymbolicLinkReplacing(source, sourceAttributes, target, copyOptions)
                copyOptions.progressListener?.invoke(getSize(sourceAttributes))
            }

            FileType.OTHER ->
                throw FileSystemException(source.toString(), null, "Cannot copy a special file")
        }
        copyAttributes(source, sourceAttributes, target, copyOptions)
    }

    /**
     * Whether a copy or move onto an existing [target] is already done, because it is the very
     * file being copied or moved.
     *
     * @throws FileAlreadyExistsException when the target is a different file and [CopyOptions]
     *   does not ask for it to be replaced.
     */
    @Throws(IOException::class)
    private fun isDone(
        source: P,
        sourceAttributes: A,
        target: P,
        targetAttributes: A,
        copyOptions: CopyOptions
    ): Boolean {
        if (isSameFile(source, sourceAttributes, target, targetAttributes)) {
            copyOptions.progressListener?.invoke(getSize(sourceAttributes))
            return true
        }
        if (!copyOptions.replaceExisting) {
            throw FileAlreadyExistsException(source.toString(), target.toString(), null)
        }
        return false
    }

    /** [copySymbolicLink], deleting an existing target only once it is in the way. */
    @Throws(IOException::class)
    private fun copySymbolicLinkReplacing(
        source: P,
        sourceAttributes: A,
        target: P,
        copyOptions: CopyOptions
    ) {
        try {
            copySymbolicLink(source, sourceAttributes, target, copyOptions)
        } catch (e: FileAlreadyExistsException) {
            if (!copyOptions.replaceExisting) {
                throw e
            }
            // Not deleted beforehand: the provider may not support links at all.
            try {
                // The target's own type is unknown here: the link was not deleted first.
                delete(target, readAttributes(target, true).let { getFileType(it) })
                copySymbolicLink(source, sourceAttributes, target, copyOptions)
            } catch (e2: IOException) {
                e2.addSuppressed(e)
                throw e2
            }
        }
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
            deleteSuppressing(writeTarget, FileType.REGULAR_FILE, e)
            throw e
        } catch (e: UnsupportedOperationException) {
            deleteSuppressing(writeTarget, FileType.REGULAR_FILE, e)
            throw e
        }
        if (isReplacing) {
            try {
                rename(writeTarget, target, FileType.REGULAR_FILE, true)
            } catch (e: IOException) {
                deleteSuppressing(writeTarget, FileType.REGULAR_FILE, e)
                throw e
            } catch (e: UnsupportedOperationException) {
                deleteSuppressing(writeTarget, FileType.REGULAR_FILE, e)
                throw e
            }
        }
    }

    @Throws(IOException::class)
    fun move(source: P, target: P, copyOptions: CopyOptions) {
        val sourceAttributes = readAttributes(source, true)
        val targetAttributes = readAttributesOrNull(target)
        if (targetAttributes != null &&
            isDone(source, sourceAttributes, target, targetAttributes, copyOptions)
        ) {
            return
        }
        if (canRename &&
            tryRename(source, sourceAttributes, target, targetAttributes != null, copyOptions)
        ) {
            copyOptions.progressListener?.invoke(getSize(sourceAttributes))
            return
        }
        if (copyOptions.atomicMove) {
            throw AtomicMoveNotSupportedException(
                source.toString(),
                target.toString(),
                "Cannot move the file in one step"
            )
        }
        copy(source, target, copyOptions.forMoveCopy())
        val fileType = getFileType(sourceAttributes)
        try {
            delete(source, fileType)
        } catch (e: IOException) {
            deleteSuppressing(target, fileType, e)
            throw e
        } catch (e: UnsupportedOperationException) {
            deleteSuppressing(target, fileType, e)
            throw e
        }
    }

    /**
     * Tries to move [source] onto [target] with a single [rename].
     *
     * @return whether the rename succeeded; a failure is only reported when
     *   [CopyOptions.atomicMove] asked for the move to happen in one step.
     */
    @Throws(IOException::class)
    private fun tryRename(
        source: P,
        sourceAttributes: A,
        target: P,
        isReplacing: Boolean,
        copyOptions: CopyOptions
    ): Boolean = try {
        rename(source, target, getFileType(sourceAttributes), isReplacing)
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

    /**
     * These options for the copy a move falls back to: the source is going away, so its
     * attributes and any symbolic link have to be carried over.
     */
    private fun CopyOptions.forMoveCopy(): CopyOptions = if (copyAttributes && noFollowLinks) {
        this
    } else {
        CopyOptions(
            replaceExisting,
            true,
            false,
            true,
            progressIntervalMillis,
            progressListener
        )
    }

    private fun deleteSuppressing(path: P, fileType: FileType, exception: Throwable) {
        try {
            delete(path, fileType)
        } catch (e: IOException) {
            exception.addSuppressed(e)
        } catch (e: UnsupportedOperationException) {
            exception.addSuppressed(e)
        }
    }
}
