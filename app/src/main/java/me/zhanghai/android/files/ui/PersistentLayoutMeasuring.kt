/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ui

import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup

// Measuring shared by PersistentBarLayout and PersistentDrawerLayout.

/**
 * The size a persistent layout takes along one axis: it has to be given an exact size, except in
 * the layout editor, where an unspecified size becomes 300 px.
 */
internal fun resolvePersistentLayoutSize(
    mode: Int,
    size: Int,
    isInEditMode: Boolean,
    layoutName: String
): Int {
    if (mode == MeasureSpec.EXACTLY) {
        return size
    }
    require(isInEditMode) { "$layoutName must be measured with MeasureSpec.EXACTLY" }
    return if (mode == MeasureSpec.UNSPECIFIED) 300 else size
}

/** The width and height a persistent layout measures itself to. */
internal fun View.resolvePersistentLayoutSizes(
    widthMeasureSpec: Int,
    heightMeasureSpec: Int,
    layoutName: String
): Pair<Int, Int> {
    val width = resolvePersistentLayoutSize(
        MeasureSpec.getMode(widthMeasureSpec),
        MeasureSpec.getSize(widthMeasureSpec),
        isInEditMode,
        layoutName
    )
    val height = resolvePersistentLayoutSize(
        MeasureSpec.getMode(heightMeasureSpec),
        MeasureSpec.getSize(heightMeasureSpec),
        isInEditMode,
        layoutName
    )
    return width to height
}

/**
 * Checks that a persistent layout has at most one bar or drawer on each of its two sides.
 */
internal class PersistentLayoutSides(
    private val kind: String,
    private val startSide: String,
    private val endSide: String
) {
    private var hasStart = false
    private var hasEnd = false

    fun add(child: Any, isStart: Boolean) {
        val hasSide = if (isStart) hasStart else hasEnd
        check(!hasSide) { "Child $child is a second ${if (isStart) startSide else endSide} $kind" }
        if (isStart) {
            hasStart = true
        } else {
            hasEnd = true
        }
    }
}

/** Measures a bar, drawer or fill child against the whole layout, less its margins. */
internal fun measurePersistentLayoutChild(
    child: View,
    widthMeasureSpec: Int,
    heightMeasureSpec: Int
) {
    val childLayoutParams = child.layoutParams as ViewGroup.MarginLayoutParams
    val childWidthSpec = ViewGroup.getChildMeasureSpec(
        widthMeasureSpec,
        childLayoutParams.leftMargin + childLayoutParams.rightMargin,
        childLayoutParams.width
    )
    val childHeightSpec = ViewGroup.getChildMeasureSpec(
        heightMeasureSpec,
        childLayoutParams.topMargin + childLayoutParams.bottomMargin,
        childLayoutParams.height
    )
    child.measure(childWidthSpec, childHeightSpec)
}

/** Checks that a child which is not a bar, drawer or fill view is a content view. */
internal fun checkPersistentLayoutContentView(child: View, isContentView: Boolean) {
    check(isContentView) {
        "Child $child does not have a valid layout_gravity - must be Gravity.LEFT," +
            " Gravity.RIGHT, Gravity.NO_GRAVITY or Gravity.FILL"
    }
}
