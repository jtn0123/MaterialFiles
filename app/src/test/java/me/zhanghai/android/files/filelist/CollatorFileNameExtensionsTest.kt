/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import java.text.Collator
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CollatorFileNameExtensionsTest {
    private val collator = Collator.getInstance(Locale.ROOT)

    private fun sorted(vararg names: String): List<String> =
        names.sortedBy { collator.getCollationKeyForFileName(it) }

    private fun compare(name: String, other: String): Int =
        collator.getCollationKeyForFileName(name)
            .compareTo(collator.getCollationKeyForFileName(other))

    @Test
    fun numbersSortByValue() {
        assertEquals(
            listOf("file1", "file2", "file10", "file100"),
            sorted("file100", "file10", "file2", "file1")
        )
    }

    @Test
    fun leadingZerosOnlyBreakTies() {
        assertEquals(listOf("a1", "a01", "a001", "a2"), sorted("a2", "a001", "a01", "a1"))
    }

    @Test
    fun aNameOfOnlyZerosSortsBeforeOtherNumbers() {
        assertEquals(listOf("0", "00", "1", "01"), sorted("01", "1", "00", "0"))
    }

    @Test
    fun theBaseNameSortsBeforeTheExtension() {
        assertEquals(listOf("report.txt", "report 2.txt"), sorted("report 2.txt", "report.txt"))
        assertEquals(listOf("report.txt", "report-2.txt"), sorted("report-2.txt", "report.txt"))
    }

    @Test
    fun numbersInsideExtensionsSortByValueToo() {
        assertTrue(compare("a.mp3", "a.mp4") < 0)
        assertTrue(compare("archive.part9.rar", "archive.part10.rar") < 0)
    }

    @Test
    fun textIsComparedByTheCollator() {
        assertTrue(compare("apple", "Banana") < 0)
        assertEquals(0, compare("same", "same"))
        assertEquals(0, compare("", ""))
    }

    @Test
    fun theKeyBytesAreACopy() {
        val key = collator.getCollationKeyForFileName("x1")
        key.toByteArray()[0] = 0x7f
        assertEquals(0, key.compareTo(collator.getCollationKeyForFileName("x1")))
    }
}
