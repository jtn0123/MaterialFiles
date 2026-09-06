/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplacementPathTest {
    @Test
    fun siblingIsHiddenInTheSameDirectoryWithAPartSuffix() {
        val target = TestPath("/pictures/holiday.jpg")
        val sibling = target.replacementSibling()
        assertEquals(target.parent, sibling.parent)
        val name = sibling.fileName.toString()
        assertTrue(name, name.startsWith(".holiday.jpg."))
        assertTrue(name, name.endsWith(".part"))
    }

    @Test
    fun siblingsAreDistinctBetweenCalls() {
        val target = TestPath("/a/b")
        assertNotEquals(target.replacementSibling(), target.replacementSibling())
    }

    @Test
    fun worksForARelativeSingleName() {
        val sibling = TestPath("notes.txt").replacementSibling()
        assertEquals(1, sibling.nameCount)
        assertTrue(sibling.fileName.toString().startsWith(".notes.txt."))
    }
}
