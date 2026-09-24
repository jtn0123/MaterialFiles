/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ui

import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isVisible
import me.zhanghai.android.files.util.fadeInUnsafe
import me.zhanghai.android.files.util.fadeOutUnsafe

/**
 * An action mode on a toolbar drawn over another one. While it is shown, [coveredView] (the
 * toolbar underneath, still visible to focus search and accessibility although hidden from
 * sight) is taken out of focus search and out of the accessibility tree, so that D-pad and
 * screen-reader navigation stay on the overlay's own buttons.
 */
class OverlayToolbarActionMode(
    bar: ViewGroup,
    toolbar: Toolbar,
    private val coveredView: ViewGroup? = null
) : ToolbarActionMode(bar, toolbar) {
    constructor(toolbar: Toolbar) : this(toolbar, toolbar)

    private var coveredDescendantFocusability = 0
    private var coveredImportantForAccessibility = 0

    init {
        bar.isVisible = false
    }

    override fun show(bar: ViewGroup, animate: Boolean) {
        coveredView?.let {
            coveredDescendantFocusability = it.descendantFocusability
            coveredImportantForAccessibility = it.importantForAccessibility
            it.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        if (animate) {
            bar.fadeInUnsafe()
        } else {
            bar.isVisible = true
        }
    }

    override fun hide(bar: ViewGroup, animate: Boolean) {
        coveredView?.let {
            it.descendantFocusability = coveredDescendantFocusability
            it.importantForAccessibility = coveredImportantForAccessibility
        }
        if (animate) {
            bar.fadeOutUnsafe()
        } else {
            bar.isVisible = false
        }
    }
}
