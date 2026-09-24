/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import android.text.Selection
import android.text.Spannable
import android.text.method.BaseMovementMethod
import android.text.style.ClickableSpan
import android.view.MotionEvent
import android.widget.TextView

/**
 * A movement method that traverses links in the text buffer and fires clicks. Unlike
 * [android.text.method.LinkMovementMethod], this will not consume touch events outside
 * [ClickableSpan]s.
 */
object ClickableMovementMethod : BaseMovementMethod() {
    override fun initialize(view: TextView, text: Spannable) {
        Selection.removeSelection(text)
    }

    override fun onTouchEvent(view: TextView, text: Spannable, event: MotionEvent): Boolean {
        if (!event.isClickableSpanTouch) {
            return false
        }
        val span = view.findClickableSpanAt(text, event)
        if (span == null) {
            Selection.removeSelection(text)
            return false
        }
        span.onTouch(view, text, event)
        return true
    }
}

internal val MotionEvent.isClickableSpanTouch: Boolean
    get() = actionMasked == MotionEvent.ACTION_DOWN || actionMasked == MotionEvent.ACTION_UP

/** Returns the [ClickableSpan] under [event] in [text] shown by this view, if any. */
internal fun TextView.findClickableSpanAt(text: Spannable, event: MotionEvent): ClickableSpan? {
    val x = event.x.toInt() - totalPaddingLeft + scrollX
    val y = event.y.toInt() - totalPaddingTop + scrollY
    val layout = layout
    if (y < 0 || y > layout.height) {
        return null
    }
    val line = layout.getLineForVertical(y)
    if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) {
        return null
    }
    val offset = layout.getOffsetForHorizontal(line, x.toFloat())
    return text.getSpans(offset, offset, ClickableSpan::class.java).firstOrNull()
}

/** Selects this span when [event] goes down on it, and clicks it when [event] goes up. */
internal fun ClickableSpan.onTouch(view: TextView, text: Spannable, event: MotionEvent) {
    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
        Selection.setSelection(text, text.getSpanStart(this), text.getSpanEnd(this))
    } else {
        onClick(view)
    }
}
