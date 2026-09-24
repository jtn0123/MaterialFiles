/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ui

import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.Toolbar
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.zhanghai.android.files.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OverlayToolbarActionModeTest {
    private val callback = object : ToolbarActionMode.Callback {
        override fun onToolbarActionModeMenuItemClicked(
            toolbarActionMode: ToolbarActionMode,
            item: MenuItem
        ): Boolean = false

        override fun onToolbarActionModeFinished(toolbarActionMode: ToolbarActionMode) {}
    }

    @Test
    fun coveredToolbarLeavesFocusSearchWhileTheOverlayIsShown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ContextThemeWrapper(
                InstrumentationRegistry.getInstrumentation().targetContext,
                R.style.Theme_MaterialFiles
            )
            val covered = Toolbar(context)
            val overlay = Toolbar(context)
            val actionMode = OverlayToolbarActionMode(overlay, overlay, covered)
            assertFalse(overlay.isShown || overlay.visibility == View.VISIBLE)

            actionMode.start(callback, animate = false)
            assertEquals(View.VISIBLE, overlay.visibility)
            assertEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, covered.descendantFocusability)
            assertEquals(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                covered.importantForAccessibility
            )

            actionMode.finish(animate = false)
            assertEquals(View.GONE, overlay.visibility)
            assertEquals(ViewGroup.FOCUS_BEFORE_DESCENDANTS, covered.descendantFocusability)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, covered.importantForAccessibility)
            assertTrue(!actionMode.isActive)
        }
    }
}
