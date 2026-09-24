/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ui

import android.content.Context
import android.view.View
import android.widget.Checkable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The checkable views the file list and the navigation drawer use to highlight a row: being checked
 * has to show up in the drawable state, which is what their backgrounds select on.
 */
@RunWith(AndroidJUnit4::class)
class CheckableViewsTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aCheckableViewPutsItsCheckedStateInItsDrawableState() {
        checkCheckable(CheckableView(context))
    }

    @Test
    fun aCheckableFrameLayoutPutsItsCheckedStateInItsDrawableState() {
        checkCheckable(CheckableFrameLayout(context))
    }

    @Test
    fun aCheckableForegroundLinearLayoutPutsItsCheckedStateInItsDrawableState() {
        checkCheckable(CheckableForegroundLinearLayout(context))
    }

    private fun <T> checkCheckable(view: T) where T : View, T : Checkable {
        assertFalse(view.isChecked)
        assertFalse(view.drawableState.contains(android.R.attr.state_checked))

        view.isChecked = true

        assertTrue(view.isChecked)
        assertTrue(
            "A checked view must report android.R.attr.state_checked",
            view.drawableState.contains(android.R.attr.state_checked)
        )

        view.toggle()

        assertFalse(view.isChecked)
        assertFalse(view.drawableState.contains(android.R.attr.state_checked))
    }
}
