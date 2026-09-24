/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import android.os.SystemClock
import android.text.Selection
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ClickableSpan
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The links of a text view can be moved between with the arrow keys, clicked with enter, and
 * touched.
 */
@RunWith(AndroidJUnit4::class)
class ClickableMovementMethodTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val clicks = mutableListOf<String>()

    @Test
    fun theArrowKeysMoveBetweenLinksAndEnterClicksTheSelectedOne() = onMainThread {
        val (view, text) = createTextView()

        assertTrue(view.pressKey(text, KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(0 to 5, text.selection)
        assertTrue(view.pressKey(text, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(10 to 16, text.selection)
        assertTrue(view.pressKey(text, KeyEvent.KEYCODE_DPAD_UP))
        // Moving up selects backwards.
        assertEquals(5 to 0, text.selection)
        assertTrue(view.pressKey(text, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertTrue(view.pressKey(text, KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(5 to 0, text.selection)

        view.pressKey(text, KeyEvent.KEYCODE_ENTER)

        assertEquals(listOf("first"), clicks)
    }

    @Test
    fun touchingALinkSelectsItAndThenClicksIt() = onMainThread {
        val (view, text) = createTextView()

        assertTrue(ClickableMovementMethod.onTouchEvent(view, text, touch(MotionEvent.ACTION_DOWN)))
        assertEquals(0 to 5, text.selection)
        assertTrue(ClickableMovementMethod.onTouchEvent(view, text, touch(MotionEvent.ACTION_UP)))
        assertEquals(listOf("first"), clicks)

        assertTrue(
            ClickableArrowKeyMovementMethod.onTouchEvent(view, text, touch(MotionEvent.ACTION_UP))
        )
        assertEquals(listOf("first", "first"), clicks)
    }

    @Test
    fun touchingOutsideTheLinksIsNotConsumedAndDropsTheSelection() = onMainThread {
        val (view, text) = createTextView()
        Selection.setSelection(text, 0, 5)

        val outside = touch(MotionEvent.ACTION_UP, y = view.height + 100f)

        assertFalse(ClickableMovementMethod.onTouchEvent(view, text, outside))
        assertEquals(-1 to -1, text.selection)
        assertFalse(
            ClickableMovementMethod.onTouchEvent(view, text, touch(MotionEvent.ACTION_MOVE))
        )
        assertTrue(clicks.isEmpty())
    }

    private fun createTextView(): Pair<TextView, Spannable> {
        val text = SpannableString("first and second")
        text.setSpan(recordingSpan("first"), 0, 5, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(recordingSpan("second"), 10, 16, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        val view = TextView(instrumentation.targetContext)
        // A selection change makes the view check whether it must resize, which reads its layout
        // params; a view that was never added to a parent has none.
        view.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        view.setText(text, TextView.BufferType.SPANNABLE)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        return view to view.text as Spannable
    }

    private fun recordingSpan(name: String) = object : ClickableSpan() {
        override fun onClick(widget: View) {
            clicks += name
        }
    }

    private val Spannable.selection: Pair<Int, Int>
        get() = Selection.getSelectionStart(this) to Selection.getSelectionEnd(this)

    private fun TextView.pressKey(text: Spannable, keyCode: Int): Boolean {
        val time = SystemClock.uptimeMillis()
        val event = KeyEvent(time, time, KeyEvent.ACTION_DOWN, keyCode, 0)
        return ClickableArrowKeyMovementMethod.onKeyDown(this, text, keyCode, event)
    }

    private fun touch(action: Int, x: Float = 5f, y: Float = 5f): MotionEvent {
        val time = SystemClock.uptimeMillis()
        return MotionEvent.obtain(time, time, action, x, y, 0)
    }

    private fun onMainThread(block: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                block()
            } catch (t: Throwable) {
                failure = t
            }
        }
        failure?.let { throw it }
    }
}
