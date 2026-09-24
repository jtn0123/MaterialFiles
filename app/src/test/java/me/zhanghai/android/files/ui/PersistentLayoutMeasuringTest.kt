/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ui

import android.view.View.MeasureSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class PersistentLayoutMeasuringTest {
    @Test
    fun anExactSizeIsTaken() {
        assertEquals(720, resolvePersistentLayoutSize(MeasureSpec.EXACTLY, 720, false, "Layout"))
        assertEquals(720, resolvePersistentLayoutSize(MeasureSpec.EXACTLY, 720, true, "Layout"))
    }

    @Test
    fun anInexactSizeIsAnErrorOutsideTheLayoutEditor() {
        for (mode in listOf(MeasureSpec.AT_MOST, MeasureSpec.UNSPECIFIED)) {
            try {
                resolvePersistentLayoutSize(mode, 720, false, "BarLayout")
                fail("expected IllegalArgumentException")
            } catch (e: IllegalArgumentException) {
                assertEquals("BarLayout must be measured with MeasureSpec.EXACTLY", e.message)
            }
        }
    }

    @Test
    fun theLayoutEditorGetsADefaultForAnUnspecifiedSize() {
        assertEquals(300, resolvePersistentLayoutSize(MeasureSpec.UNSPECIFIED, 0, true, "Layout"))
        assertEquals(500, resolvePersistentLayoutSize(MeasureSpec.AT_MOST, 500, true, "Layout"))
    }

    @Test
    fun oneBarOnEachSideIsFine() {
        val sides = PersistentLayoutSides("bar", "top", "bottom")

        sides.add("toolbar", true)
        sides.add("bottomBar", false)
    }

    @Test
    fun aSecondBarOnTheSameSideIsAnError() {
        val sides = PersistentLayoutSides("drawer", "left", "right")
        sides.add("first", true)
        sides.add("right", false)

        try {
            sides.add("second", true)
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("Child second is a second left drawer", e.message)
        }
        try {
            sides.add("third", false)
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("Child third is a second right drawer", e.message)
        }
    }
}
