/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ui

import android.view.Gravity
import android.view.View
import androidx.core.view.children

/** Which child of a [PersistentBarLayout] is what, by its layout gravity. */
internal fun PersistentBarLayout.findBarView(gravity: Int): View? {
    val verticalGravity = gravity and Gravity.VERTICAL_GRAVITY_MASK
    for (child in children) {
        val childVerticalGravity = getChildVerticalGravity(child)
        if (childVerticalGravity == verticalGravity) {
            return child
        }
    }
    return null
}

internal fun PersistentBarLayout.isBarView(child: View): Boolean {
    val verticalGravity = getChildVerticalGravity(child)
    return verticalGravity == Gravity.TOP || verticalGravity == Gravity.BOTTOM
}

internal fun PersistentBarLayout.isTopBarView(barView: View): Boolean {
    val verticalGravity = getChildVerticalGravity(barView)
    return verticalGravity == Gravity.TOP
}

internal fun PersistentBarLayout.isContentView(child: View): Boolean =
    getChildGravity(child) == Gravity.NO_GRAVITY

internal fun PersistentBarLayout.isFillView(child: View): Boolean =
    getChildGravity(child) == Gravity.FILL

private fun PersistentBarLayout.getChildGravity(child: View): Int =
    (child.layoutParams as PersistentBarLayout.LayoutParams).gravity

private fun PersistentBarLayout.getChildVerticalGravity(child: View): Int =
    getChildGravity(child) and Gravity.VERTICAL_GRAVITY_MASK
