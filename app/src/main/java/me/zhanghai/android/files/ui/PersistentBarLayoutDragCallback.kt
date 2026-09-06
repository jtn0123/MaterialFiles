/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ui

import android.view.Gravity
import android.view.View
import androidx.core.view.isInvisible
import androidx.customview.widget.ViewDragHelper

/** Slides the bar with [gravity] of [layout] in and out; it never captures touches. */
internal class PersistentBarLayoutDragCallback(
    private val layout: PersistentBarLayout,
    private val gravity: Int
) : ViewDragHelper.Callback() {
    override fun tryCaptureView(child: View, pointerId: Int): Boolean = false

    override fun onViewPositionChanged(changedView: View, left: Int, top: Int, dx: Int, dy: Int) {
        val childRange = getViewVerticalDragRange(changedView)
        val childLayoutParams = changedView.layoutParams as PersistentBarLayout.LayoutParams
        if (layout.isTopBarView(changedView)) {
            childLayoutParams.offset = (top - childLayoutParams.topMargin + childRange)
                .toFloat() / childRange
        } else {
            val height = layout.height
            childLayoutParams.offset = (
                (childLayoutParams.topMargin + height - top).toFloat() /
                    childRange
                )
        }
        changedView.isInvisible = childLayoutParams.offset <= 0
        layout.updateContentViewsWindowInsets()
        layout.measureContentViews()
        layout.layoutContentViews()
    }

    override fun onViewCaptured(capturedChild: View, activePointerId: Int) {
        closeOtherBar()
    }

    private fun closeOtherBar() {
        val otherGravity = if (gravity == Gravity.TOP) Gravity.BOTTOM else Gravity.TOP
        val otherBar = layout.findBarView(otherGravity)
        otherBar?.let { layout.hideBar(it) }
    }

    override fun getViewVerticalDragRange(child: View): Int {
        if (!layout.isBarView(child)) {
            return 0
        }
        val childLayoutParams = child.layoutParams as PersistentBarLayout.LayoutParams
        return childLayoutParams.topMargin + child.height + childLayoutParams.bottomMargin
    }

    override fun clampViewPositionHorizontal(child: View, left: Int, dx: Int): Int = child.left

    override fun clampViewPositionVertical(child: View, top: Int, dy: Int): Int =
        if (layout.isTopBarView(child)) {
            top.coerceIn(-getViewVerticalDragRange(child)..0)
        } else {
            val height = layout.height
            top.coerceIn(height - getViewVerticalDragRange(child)..height)
        }
}
