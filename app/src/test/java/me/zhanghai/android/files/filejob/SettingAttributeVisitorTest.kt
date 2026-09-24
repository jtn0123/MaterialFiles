/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import java.io.IOException
import java8.nio.file.FileVisitResult
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.FileTime
import me.zhanghai.android.files.provider.common.TestPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class SettingAttributeVisitorTest {
    private val visited = mutableListOf<Pair<Path, Boolean>>()

    private val visitor = SettingAttributeVisitor { path, attributes ->
        visited += path to attributes.isDirectory
    }

    @Test
    fun aDirectoryIsSetBeforeItsChildrenLikeAFile() {
        val directory = TestPath("/directory")
        val file = TestPath("/directory/file")
        assertEquals(
            FileVisitResult.CONTINUE,
            visitor.preVisitDirectory(directory, TestAttributes(true))
        )
        assertEquals(FileVisitResult.CONTINUE, visitor.visitFile(file, TestAttributes(false)))
        assertEquals(FileVisitResult.CONTINUE, visitor.postVisitDirectory(directory, null))
        assertEquals(listOf<Pair<Path, Boolean>>(directory to true, file to false), visited)
    }

    @Test
    fun aFileThatCannotBeVisitedFailsTheWalk() {
        val exception = IOException()
        try {
            visitor.visitFileFailed(TestPath("/file"), exception)
            fail("the failure was swallowed")
        } catch (e: IOException) {
            assertSame(exception, e)
        }
    }

    @Test
    fun aDirectoryThatCannotBeListedFailsTheWalk() {
        val exception = IOException()
        try {
            visitor.postVisitDirectory(TestPath("/directory"), exception)
            fail("the failure was swallowed")
        } catch (e: IOException) {
            assertSame(exception, e)
        }
    }

    private class TestAttributes(private val isDirectory: Boolean) : BasicFileAttributes {
        override fun lastModifiedTime(): FileTime = FileTime.fromMillis(0)

        override fun lastAccessTime(): FileTime = FileTime.fromMillis(0)

        override fun creationTime(): FileTime = FileTime.fromMillis(0)

        override fun isRegularFile(): Boolean = !isDirectory

        override fun isDirectory(): Boolean = isDirectory

        override fun isSymbolicLink(): Boolean = false

        override fun isOther(): Boolean = false

        override fun size(): Long = 0

        override fun fileKey(): Any = throw UnsupportedOperationException()
    }
}
