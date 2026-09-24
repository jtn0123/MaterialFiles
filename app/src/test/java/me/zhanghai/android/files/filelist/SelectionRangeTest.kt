/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelectionRangeTest {
    private fun range(itemCount: Int, vararg selected: Int): IntRange? =
        selectionRange(itemCount) { it in selected }

    @Test
    fun spansFirstToLastSelected() {
        assertEquals(2..7, range(10, 2, 7))
        assertEquals(2..7, range(10, 7, 4, 2))
        assertEquals(0..9, range(10, 0, 9))
    }

    @Test
    fun nothingToSelectWithFewerThanTwoPositions() {
        assertNull(range(10))
        assertNull(range(10, 3))
        assertNull(range(0))
    }

    @Test
    fun adjacentSelectionsStillFormARange() {
        assertEquals(3..4, range(10, 3, 4))
    }
}
