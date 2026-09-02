/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach

/**
 * The inset types that made up the deprecated "system window insets": the system bars and the
 * display cutout. Using the same set keeps the layouts behaving as they did before the migration.
 */
private val SYSTEM_BARS_INSET_TYPES =
    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()

val WindowInsetsCompat.systemBarsInsets: Insets
    get() = getInsets(SYSTEM_BARS_INSET_TYPES)

fun WindowInsetsCompat.replaceSystemBarsInsets(
    left: Int,
    top: Int,
    right: Int,
    bottom: Int
): WindowInsetsCompat = WindowInsetsCompat.Builder(this)
    .setInsets(SYSTEM_BARS_INSET_TYPES, Insets.of(left, top, right, bottom))
    .build()

fun systemBarsInsetsOf(left: Int, top: Int, right: Int, bottom: Int): WindowInsetsCompat =
    WindowInsetsCompat.Builder()
        .setInsets(SYSTEM_BARS_INSET_TYPES, Insets.of(left, top, right, bottom))
        .build()

/** Adds the selected system bars insets to the padding this view has now. */
fun View.applySystemWindowInsetsToPadding(
    left: Boolean = false,
    top: Boolean = false,
    right: Boolean = false,
    bottom: Boolean = false
) {
    val initialPadding = Insets.of(paddingLeft, paddingTop, paddingRight, paddingBottom)
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val systemBarsInsets = insets.systemBarsInsets
        view.setPadding(
            initialPadding.left + if (left) systemBarsInsets.left else 0,
            initialPadding.top + if (top) systemBarsInsets.top else 0,
            initialPadding.right + if (right) systemBarsInsets.right else 0,
            initialPadding.bottom + if (bottom) systemBarsInsets.bottom else 0
        )
        insets
    }
    doOnAttach { it.requestApplyInsets() }
}
