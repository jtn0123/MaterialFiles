/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ui

import android.graphics.Rect
import android.view.View
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.WindowInsetsCompat
import me.zhanghai.android.fastscroll.FastScroller
import me.zhanghai.android.files.util.systemBarsInsets

class ScrollingViewOnApplyWindowInsetsListener(
    view: View,
    private val fastScroller: FastScroller? = null
) : OnApplyWindowInsetsListener {
    private val initialPadding =
        Rect(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)

    init {
        fastScroller?.setPadding(0, 0, 0, 0)
    }

    override fun onApplyWindowInsets(view: View, insets: WindowInsetsCompat): WindowInsetsCompat {
        val bottom = insets.systemBarsInsets.bottom
        view.setPadding(
            initialPadding.left,
            initialPadding.top,
            initialPadding.right,
            initialPadding.bottom + bottom
        )
        fastScroller?.setPadding(0, 0, 0, bottom)
        return insets
    }
}
