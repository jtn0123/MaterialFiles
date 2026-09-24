/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import java.io.IOException
import java.io.InterruptedIOException
import java8.nio.file.DirectoryIteratorException
import java8.nio.file.FileVisitOption
import java8.nio.file.FileVisitResult
import java8.nio.file.FileVisitor
import java8.nio.file.Files
import java8.nio.file.LinkOption
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.util.logWarning

object WalkFileTreeSearchable {
    @Throws(IOException::class)
    fun search(
        directory: Path,
        query: String,
        intervalMillis: Long,
        listener: (List<Path>) -> Unit
    ) {
        // We cannot use Files.find() or Files.walk() because it cannot ignore exceptions.
        val visitor = SearchVisitor(directory, query, intervalMillis, listener)
        walkFileTreeForSearch(directory, visitor)
        visitor.reportRemaining()
    }

    // This method traverses the first level first, before diving into child directories.
    // FileVisitResult returned from visitor may be ignored and always considered CONTINUE.
    @Throws(IOException::class)
    private fun walkFileTreeForSearch(start: Path, visitor: FileVisitor<in Path>) {
        val attributes = try {
            readAttributesForSearch(start)
        } catch (e: IOException) {
            visitor.visitFileFailed(start, e)
            return
        }
        if (!attributes.isDirectory) {
            visitor.visitFile(start, attributes)
            return
        }
        val directories = visitFirstLevel(start, attributes, visitor) ?: return
        for (path in directories) {
            Files.walkFileTree(
                path,
                setOf(FileVisitOption.FOLLOW_LINKS),
                Int.MAX_VALUE,
                SubtreeVisitor(path, visitor)
            )
        }
        visitor.postVisitDirectory(start, null)
    }

    // Follows a symbolic link, or falls back to the link itself when its target cannot be read.
    @Throws(IOException::class)
    private fun readAttributesForSearch(path: Path): BasicFileAttributes = try {
        path.readAttributes(BasicFileAttributes::class.java)
    } catch (ignored: IOException) {
        path.readAttributes(BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    }

    /**
     * Visits the children of [start] and returns those that are directories, or null if the
     * directory could not be listed to the end.
     */
    @Throws(IOException::class)
    private fun visitFirstLevel(
        start: Path,
        attributes: BasicFileAttributes,
        visitor: FileVisitor<in Path>
    ): List<Path>? {
        val directoryStream = try {
            start.newDirectoryStream()
        } catch (e: IOException) {
            visitor.visitFileFailed(start, e)
            return null
        }
        val directories = mutableListOf<Path>()
        directoryStream.use {
            visitor.preVisitDirectory(start, attributes)
            try {
                for (path in directoryStream) {
                    val pathAttributes = try {
                        readAttributesForSearch(path)
                    } catch (e: IOException) {
                        visitor.visitFileFailed(path, e)
                        continue
                    }
                    visitor.visitFile(path, pathAttributes)
                    if (pathAttributes.isDirectory) {
                        directories.add(path)
                    }
                }
            } catch (e: DirectoryIteratorException) {
                visitor.postVisitDirectory(start, e.cause)
                return null
            }
        }
        return directories
    }

    @Throws(InterruptedIOException::class)
    private fun throwIfInterrupted() {
        if (Thread.interrupted()) {
            throw InterruptedIOException()
        }
    }

    private class SearchVisitor(
        private val directory: Path,
        private val query: String,
        private val intervalMillis: Long,
        private val listener: (List<Path>) -> Unit
    ) : FileVisitor<Path> {
        private val paths = mutableListOf<Path>()

        private var lastProgressMillis = System.currentTimeMillis()

        @Throws(InterruptedIOException::class)
        override fun preVisitDirectory(
            directory: Path,
            attributes: BasicFileAttributes
        ): FileVisitResult {
            visit(directory)
            throwIfInterrupted()
            return FileVisitResult.CONTINUE
        }

        @Throws(InterruptedIOException::class)
        override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
            visit(file)
            throwIfInterrupted()
            return FileVisitResult.CONTINUE
        }

        @Throws(InterruptedIOException::class)
        override fun visitFileFailed(file: Path, exception: IOException): FileVisitResult {
            if (exception is InterruptedIOException) {
                throw exception
            }
            exception.logWarning("WalkFileTreeSearchable", "visitFileFailed($file)")
            visit(file)
            throwIfInterrupted()
            return FileVisitResult.CONTINUE
        }

        @Throws(InterruptedIOException::class)
        override fun postVisitDirectory(directory: Path, exception: IOException?): FileVisitResult {
            if (exception is InterruptedIOException) {
                throw exception
            }
            exception?.logWarning("WalkFileTreeSearchable", "postVisitDirectory")
            throwIfInterrupted()
            return FileVisitResult.CONTINUE
        }

        private fun visit(path: Path) {
            // Exclude the directory being searched.
            if (path == directory) {
                return
            }
            val fileName = path.fileName
            if (fileName != null && fileName.toString().contains(query, true)) {
                paths.add(path)
            }
            if (paths.isNotEmpty()) {
                val currentTimeMillis = System.currentTimeMillis()
                if (currentTimeMillis >= lastProgressMillis + intervalMillis) {
                    listener(paths)
                    lastProgressMillis = currentTimeMillis
                    paths.clear()
                }
            }
        }

        fun reportRemaining() {
            if (paths.isNotEmpty()) {
                listener(paths)
            }
        }
    }

    /**
     * Hands a subtree walked by [Files.walkFileTree] to [visitor], without its [root] which was
     * already visited as part of the first level.
     */
    private class SubtreeVisitor(
        private val root: Path,
        private val visitor: FileVisitor<in Path>
    ) : FileVisitor<Path> {
        @Throws(InterruptedIOException::class)
        override fun preVisitDirectory(
            directory: Path,
            attributes: BasicFileAttributes
        ): FileVisitResult {
            if (directory == root) {
                return FileVisitResult.CONTINUE
            }
            return visitor.preVisitDirectory(directory, attributes)
        }

        @Throws(InterruptedIOException::class)
        override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
            if (file == root) {
                return FileVisitResult.CONTINUE
            }
            return visitor.visitFile(file, attributes)
        }

        @Throws(InterruptedIOException::class)
        override fun visitFileFailed(file: Path, exception: IOException): FileVisitResult {
            if (file == root) {
                // We are searching and ignoring errors, so just print it.
                exception.logWarning("WalkFileTreeSearchable", "visitFileFailed($file)")
                return FileVisitResult.CONTINUE
            }
            return visitor.visitFileFailed(file, exception)
        }

        @Throws(InterruptedIOException::class)
        override fun postVisitDirectory(directory: Path, exception: IOException?): FileVisitResult {
            if (directory == root) {
                // We are searching and ignoring errors, so just print it.
                exception?.logWarning("WalkFileTreeSearchable", "postVisitDirectory")
                return FileVisitResult.CONTINUE
            }
            return visitor.postVisitDirectory(directory, exception)
        }
    }
}
