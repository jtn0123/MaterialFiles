/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ui

import android.view.Gravity
import android.view.View
import androidx.core.view.isInvisible
import androidx.customview.widget.ViewDragHelper

/** Slides the drawer with [gravity] of [layout] open and closed; it never captures touches. */
internal class PersistentDrawerLayoutDragCallback(
    private val layout: PersistentDrawerLayout,
    private val gravity: Int
) : ViewDragHelper.Callback() {
    override fun tryCaptureView(child: View, pointerId: Int): Boolean = false

    override fun onViewPositionChanged(changedView: View, left: Int, top: Int, dx: Int, dy: Int) {
        val childRange = getViewHorizontalDragRange(changedView)
        val childLayoutParams = changedView.layoutParams as PersistentDrawerLayout.LayoutParams
        if (layout.isLeftDrawerView(changedView)) {
            childLayoutParams.offset = (left - childLayoutParams.leftMargin + childRange)
                .toFloat() / childRange
        } else {
            val width = layout.width
            childLayoutParams.offset = (
                (childLayoutParams.leftMargin + width - left).toFloat() /
                    childRange
                )
        }
        changedView.isInvisible = childLayoutParams.offset <= 0
        layout.updateContentViewsWindowInsets()
        layout.measureContentViews()
        layout.layoutContentViews()
    }

    override fun onViewCaptured(capturedChild: View, activePointerId: Int) {
        closeOtherDrawer()
    }

    private fun closeOtherDrawer() {
        val otherGravity = if (gravity == Gravity.LEFT) Gravity.RIGHT else Gravity.LEFT
        val otherDrawer = layout.findDrawerView(otherGravity)
        otherDrawer?.let { layout.closeDrawer(it) }
    }

    override fun getViewHorizontalDragRange(child: View): Int {
        if (!layout.isDrawerView(child)) {
            return 0
        }
        val childLayoutParams = child.layoutParams as PersistentDrawerLayout.LayoutParams
        return childLayoutParams.leftMargin + child.width + childLayoutParams.rightMargin
    }

    override fun clampViewPositionHorizontal(child: View, left: Int, dx: Int): Int =
        if (layout.isLeftDrawerView(child)) {
            left.coerceIn(-getViewHorizontalDragRange(child)..0)
        } else {
            val width = layout.width
            left.coerceIn(width - getViewHorizontalDragRange(child)..width)
        }

    override fun clampViewPositionVertical(child: View, top: Int, dy: Int): Int = child.top
}
