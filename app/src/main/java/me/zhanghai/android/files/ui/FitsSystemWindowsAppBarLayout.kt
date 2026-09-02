/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ui

import android.content.Context
import android.util.AttributeSet
import androidx.annotation.AttrRes
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.OnWindowInsetChangedAppBarLayout
import me.zhanghai.android.files.util.systemBarsInsets

open class FitsSystemWindowsAppBarLayout : OnWindowInsetChangedAppBarLayout {
    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(
        context: Context,
        attrs: AttributeSet?,
        @AttrRes defStyleAttr: Int
    ) : super(context, attrs, defStyleAttr)

    init {
        fitsSystemWindows = true
    }

    override fun onWindowInsetChanged(insets: WindowInsetsCompat): WindowInsetsCompat {
        val systemBarsInsets = insets.systemBarsInsets
        updatePadding(left = systemBarsInsets.left, right = systemBarsInsets.right)
        return super.onWindowInsetChanged(insets)
    }
}
