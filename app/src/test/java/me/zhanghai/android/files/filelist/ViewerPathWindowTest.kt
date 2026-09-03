/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerPathWindowTest {
    private val items = (0 until 10).toList()

    @Test
    fun everythingFitsWhenTheBudgetAllows() {
        assertEquals(items to 3, windowWithinBudget(items, 3, 100) { 1 })
    }

    @Test
    fun windowIsCentredOnThePosition() {
        val (window, position) = windowWithinBudget(items, 5, 5) { 1 }
        assertEquals(listOf(3, 4, 5, 6, 7), window)
        assertEquals(2, position)
    }

    @Test
    fun windowShiftsWhenThePositionIsNearAnEdge() {
        val (window, position) = windowWithinBudget(items, 0, 3) { 1 }
        assertEquals(listOf(0, 1, 2), window)
        assertEquals(0, position)
        val (endWindow, endPosition) = windowWithinBudget(items, 9, 3) { 1 }
        assertEquals(listOf(7, 8, 9), endWindow)
        assertEquals(2, endPosition)
    }

    @Test
    fun theItemAtThePositionIsAlwaysIncluded() {
        val (window, position) = windowWithinBudget(items, 4, 0) { 1 }
        assertEquals(listOf(4), window)
        assertEquals(0, position)
    }

    @Test
    fun expensiveNeighboursAreSkippedOnThatSideOnly() {
        // Item 6 is too expensive, so the window can only grow towards the start.
        val (window, position) = windowWithinBudget(items, 5, 4) { if (it == 6) 100 else 1 }
        assertEquals(listOf(2, 3, 4, 5), window)
        assertEquals(3, position)
    }
}
