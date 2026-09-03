/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ui

import android.view.Gravity
import android.view.View
import androidx.core.view.children

/** Which child of a [PersistentDrawerLayout] is what, by its layout gravity. */
internal fun PersistentDrawerLayout.findDrawerView(gravity: Int): View? {
    val horizontalGravity = (
        Gravity.getAbsoluteGravity(gravity, layoutDirection)
            and Gravity.HORIZONTAL_GRAVITY_MASK
        )
    for (child in children) {
        val childHorizontalGravity = getChildAbsoluteHorizontalGravity(child)
        if (childHorizontalGravity == horizontalGravity) {
            return child
        }
    }
    return null
}

internal fun PersistentDrawerLayout.isDrawerView(child: View): Boolean {
    val horizontalGravity = getChildAbsoluteHorizontalGravity(child)
    return horizontalGravity == Gravity.LEFT || horizontalGravity == Gravity.RIGHT
}

internal fun PersistentDrawerLayout.isLeftDrawerView(drawerView: View): Boolean {
    val horizontalGravity = getChildAbsoluteHorizontalGravity(drawerView)
    return horizontalGravity == Gravity.LEFT
}

internal fun PersistentDrawerLayout.isContentView(child: View): Boolean =
    getChildGravity(child) == Gravity.NO_GRAVITY

internal fun PersistentDrawerLayout.isFillView(child: View): Boolean =
    getChildGravity(child) == Gravity.FILL

private fun PersistentDrawerLayout.getChildGravity(child: View): Int =
    (child.layoutParams as PersistentDrawerLayout.LayoutParams).gravity

private fun PersistentDrawerLayout.getChildAbsoluteHorizontalGravity(child: View): Int = (
    Gravity.getAbsoluteGravity(getChildGravity(child), layoutDirection)
        and Gravity.HORIZONTAL_GRAVITY_MASK
    )
