/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import me.zhanghai.android.files.provider.common.toByteString
import org.junit.Assert.assertEquals
import org.junit.Test

class DuplicateCountTest {
    @Test
    fun aNameWithoutACountGetsTheFirstOne() {
        assertEquals("photo (1).jpg", duplicate("photo.jpg", "photo".length))
    }

    @Test
    fun anExistingCountIsIncremented() {
        assertEquals("photo (3).jpg", duplicate("photo (2).jpg", "photo (2)".length))
        assertEquals("Folder (10)", duplicate("Folder (9)", "Folder (9)".length))
    }

    @Test
    fun theCountIsFoundWhereItEnds() {
        val info = getDuplicateCountInfo("a (12).txt".toByteString(), "a (12)".length)
        assertEquals(1, info.countStart)
        assertEquals(6, info.countEnd)
        assertEquals(12, info.count)
    }

    @Test
    fun somethingThatOnlyLooksLikeACountIsKept() {
        // No space before the parenthesis.
        assertEquals("a(2) (1)", duplicate("a(2)", 4))
        // No digits.
        assertEquals("a () (1)", duplicate("a ()", 4))
        // Not only digits.
        assertEquals("a (2a) (1)", duplicate("a (2a)", 6))
        // No opening parenthesis.
        assertEquals("a 2) (1)", duplicate("a 2)", 4))
        // Nothing before the count, which would then be the whole name.
        assertEquals(" (2) (1)", duplicate(" (2)", 4))
        // A number too large for a count.
        assertEquals("a (99999999999) (1)", duplicate("a (99999999999)", 15))
    }

    @Test
    fun anEmptyNameGetsACount() {
        assertEquals(" (1)", duplicate("", 0))
    }

    private fun duplicate(fileName: String, countEnd: Int): String {
        val name = fileName.toByteString()
        val info = getDuplicateCountInfo(name, countEnd)
        return setDuplicateCount(name, info, info.count + 1).toString()
    }
}
