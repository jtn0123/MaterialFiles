/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Moving between the links of a text with the arrow keys. */
class ClickableSpanNavigationTest {
    private val spans = listOf(0 to 4, 10 to 14, 20 to 24)

    @Test
    fun theSelectionIsOrdered() {
        assertEquals(3 to 7, navigationSelection(7, 3, false, 100, 0, 100))
    }

    @Test
    fun nothingSelectedStaysNothingUnlessTheFocusCameFromBelow() {
        assertEquals(-1 to -1, navigationSelection(-1, -1, false, 100, 0, 100))
        assertEquals(100 to 100, navigationSelection(-1, -1, true, 100, 0, 100))
    }

    @Test
    fun aSelectionScrolledOutOfSightIsPastThatEndOfTheVisibleText() {
        assertEquals(
            Int.MAX_VALUE to Int.MAX_VALUE,
            navigationSelection(60, 70, false, 100, 0, 50)
        )
        assertEquals(-1 to -1, navigationSelection(5, 8, false, 100, 20, 50))
        // Coming from below with the end of the text out of sight is past the visible end too.
        assertEquals(Int.MAX_VALUE to Int.MAX_VALUE, navigationSelection(-1, -1, true, 100, 0, 50))
    }

    @Test
    fun upSelectsTheLinkEndingLastBeforeTheSelection() {
        assertEquals(10 to 14, previousSpanBounds(spans, 20, 24))
        assertEquals(0 to 4, previousSpanBounds(spans, 10, 14))
        assertNull(previousSpanBounds(spans, 0, 4))
    }

    @Test
    fun upWithNothingSelectedSelectsTheLastLink() {
        assertEquals(20 to 24, previousSpanBounds(spans, -1, -1))
        assertEquals(20 to 24, previousSpanBounds(spans, 100, 100))
        assertNull(previousSpanBounds(emptyList(), -1, -1))
    }

    @Test
    fun downSelectsTheLinkStartingFirstAfterTheSelection() {
        assertEquals(10 to 14, nextSpanBounds(spans, 0, 4))
        assertEquals(20 to 24, nextSpanBounds(spans, 10, 14))
        assertNull(nextSpanBounds(spans, 20, 24))
    }

    @Test
    fun downWithNothingSelectedSelectsTheFirstLink() {
        assertEquals(0 to 4, nextSpanBounds(spans, -1, -1))
        assertEquals(0 to 4, nextSpanBounds(spans, Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test
    fun tiesGoToTheFirstLinkFound() {
        val overlapping = listOf(5 to 9, 2 to 9, 5 to 7)

        assertEquals(5 to 9, previousSpanBounds(overlapping, -1, -1))
        assertEquals(5 to 9, nextSpanBounds(listOf(5 to 9, 5 to 7), -1, -1))
    }
}
