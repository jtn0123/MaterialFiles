/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import java.text.Collator
import java.util.Locale
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.FileTime
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.provider.common.TestPath
import org.junit.Assert.assertEquals
import org.junit.Test

class FileSortOptionsTest {
    private val collator = Collator.getInstance(Locale.ROOT)

    private val files = listOf(
        file("b.txt", size = 30, lastModified = 1),
        file("a.png", size = 10, lastModified = 3),
        file(".hidden", size = 20, lastModified = 2),
        file("c", isDirectory = true, size = 0, lastModified = 4),
        file("#draft.TXT", size = 5, lastModified = 5)
    )

    private fun sort(
        by: FileSortOptions.By,
        order: FileSortOptions.Order = FileSortOptions.Order.ASCENDING,
        isDirectoriesFirst: Boolean = false
    ): List<String> = files.sortedWith(
        FileSortOptions(by, order, isDirectoriesFirst).createComparator()
    ).map { it.name }

    @Test
    fun namesStartingWithADotOrAHashSortLast() {
        assertEquals(
            listOf("a.png", "b.txt", "c", "#draft.TXT", ".hidden"),
            sort(FileSortOptions.By.NAME)
        )
    }

    @Test
    fun typeSortsByExtensionIgnoringCaseThenByName() {
        assertEquals(
            listOf("c", ".hidden", "a.png", "b.txt", "#draft.TXT"),
            sort(FileSortOptions.By.TYPE)
        )
    }

    @Test
    fun sizeAndLastModifiedSortByTheirValue() {
        assertEquals(
            listOf("c", "#draft.TXT", "a.png", ".hidden", "b.txt"),
            sort(FileSortOptions.By.SIZE)
        )
        assertEquals(
            listOf("b.txt", ".hidden", "a.png", "c", "#draft.TXT"),
            sort(FileSortOptions.By.LAST_MODIFIED)
        )
    }

    @Test
    fun descendingReversesTheWholeOrder() {
        assertEquals(
            listOf(".hidden", "#draft.TXT", "c", "b.txt", "a.png"),
            sort(FileSortOptions.By.NAME, FileSortOptions.Order.DESCENDING)
        )
    }

    @Test
    fun directoriesFirstStaysFirstInEitherOrder() {
        assertEquals(
            listOf("c", "a.png", "b.txt", "#draft.TXT", ".hidden"),
            sort(FileSortOptions.By.NAME, isDirectoriesFirst = true)
        )
        assertEquals(
            listOf("c", ".hidden", "#draft.TXT", "b.txt", "a.png"),
            sort(
                FileSortOptions.By.NAME,
                FileSortOptions.Order.DESCENDING,
                isDirectoriesFirst = true
            )
        )
    }

    private fun file(
        name: String,
        isDirectory: Boolean = false,
        size: Long,
        lastModified: Long
    ): FileItem = FileItem(
        TestPath("/dir/$name"),
        collator.getCollationKeyForFileName(name),
        TestAttributes(isDirectory, size, lastModified),
        null,
        null,
        false,
        if (isDirectory) MimeType.DIRECTORY else MimeType.GENERIC
    )

    private class TestAttributes(
        private val isDirectory: Boolean,
        private val size: Long,
        private val lastModified: Long
    ) : BasicFileAttributes {
        override fun lastModifiedTime(): FileTime = FileTime.fromMillis(lastModified)

        override fun lastAccessTime(): FileTime = FileTime.fromMillis(lastModified)

        override fun creationTime(): FileTime = FileTime.fromMillis(lastModified)

        override fun isRegularFile(): Boolean = !isDirectory

        override fun isDirectory(): Boolean = isDirectory

        override fun isSymbolicLink(): Boolean = false

        override fun isOther(): Boolean = false

        override fun size(): Long = size

        override fun fileKey(): Any? = null
    }
}
