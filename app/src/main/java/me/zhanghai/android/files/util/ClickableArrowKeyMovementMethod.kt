/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import android.text.NoCopySpan.Concrete
import android.text.Selection
import android.text.Spannable
import android.text.method.ArrowKeyMovementMethod
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.TextView

/**
 * @see LinkMovementMethod
 * @see ArrowKeyMovementMethod
 * @see ClickableMovementMethod
 */
object ClickableArrowKeyMovementMethod : ArrowKeyMovementMethod() {
    private const val CLICK = 1
    private const val UP = 2
    private const val DOWN = 3

    private val FROM_BELOW = Concrete()

    override fun initialize(view: TextView, text: Spannable) {
        super.initialize(view, text)

        text.removeSpan(FROM_BELOW)
    }

    override fun onTakeFocus(view: TextView, text: Spannable, direction: Int) {
        super.onTakeFocus(view, text, direction)

        if (direction.hasBits(View.FOCUS_BACKWARD)) {
            text.setSpan(FROM_BELOW, 0, 0, Spannable.SPAN_POINT_POINT)
        } else {
            text.removeSpan(FROM_BELOW)
        }
    }

    override fun handleMovementKey(
        view: TextView,
        text: Spannable,
        keyCode: Int,
        movementMetaState: Int,
        event: KeyEvent
    ): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (KeyEvent.metaStateHasNoModifiers(movementMetaState)) {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
                        action(CLICK, view, text)
                    ) {
                        return true
                    }
                }
            }
        }
        return super.handleMovementKey(view, text, keyCode, movementMetaState, event)
    }

    override fun up(view: TextView, text: Spannable): Boolean {
        if (action(UP, view, text)) {
            return true
        }
        return super.up(view, text)
    }

    override fun down(view: TextView, text: Spannable): Boolean {
        if (action(DOWN, view, text)) {
            return true
        }
        return super.down(view, text)
    }

    override fun left(view: TextView, text: Spannable): Boolean {
        if (action(UP, view, text)) {
            return true
        }
        return super.left(view, text)
    }

    override fun right(view: TextView, text: Spannable): Boolean {
        if (action(DOWN, view, text)) {
            return true
        }
        return super.right(view, text)
    }

    private fun action(what: Int, view: TextView, text: Spannable): Boolean {
        val layout = view.layout
        val padding = view.totalPaddingTop + view.totalPaddingBottom
        val areaTop = view.scrollY
        val areaBottom = areaTop + view.height - padding
        val lineTop = layout.getLineForVertical(areaTop)
        val lineBottom = layout.getLineForVertical(areaBottom)
        val first = layout.getLineStart(lineTop)
        val last = layout.getLineEnd(lineBottom)
        val (selectionStart, selectionEnd) = navigationSelection(
            Selection.getSelectionStart(text),
            Selection.getSelectionEnd(text),
            text.getSpanStart(FROM_BELOW) >= 0,
            text.length,
            first,
            last
        )
        val candidates = text.getSpans(first, last, ClickableSpan::class.java)
            .map { text.getSpanStart(it) to text.getSpanEnd(it) }
        return when (what) {
            CLICK -> {
                clickSelectedSpan(view, text, selectionStart, selectionEnd)
                // Like LinkMovementMethod, a click doesn't consume the key.
                false
            }

            UP -> {
                val (start, end) = previousSpanBounds(candidates, selectionStart, selectionEnd)
                    ?: return false
                // Selected backwards when moving up, like LinkMovementMethod.
                Selection.setSelection(text, end, start)
                true
            }

            DOWN -> {
                val (start, end) = nextSpanBounds(candidates, selectionStart, selectionEnd)
                    ?: return false
                Selection.setSelection(text, start, end)
                true
            }

            else -> false
        }
    }

    private fun clickSelectedSpan(
        view: TextView,
        text: Spannable,
        selectionStart: Int,
        selectionEnd: Int
    ) {
        if (selectionStart == selectionEnd) {
            return
        }
        val span = text.getSpans(selectionStart, selectionEnd, ClickableSpan::class.java)
            .singleOrNull() ?: return
        span.onClick(view)
    }

    override fun onTouchEvent(view: TextView, text: Spannable, event: MotionEvent): Boolean {
        // Unlike ClickableMovementMethod, a touch outside a span keeps the selection.
        if (event.isClickableSpanTouch) {
            val span = view.findClickableSpanAt(text, event)
            if (span != null) {
                span.onTouch(view, text, event)
                return true
            }
        }
        return super.onTouchEvent(view, text, event)
    }
}
