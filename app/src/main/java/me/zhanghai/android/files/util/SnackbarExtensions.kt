/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import android.view.View
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import com.google.android.material.snackbar.Snackbar

/**
 * Shows [text] in a snackbar with an [actionRes] button that runs [action], for an error the user
 * can act on right away; a toast would be gone before they could, and cannot hold a button.
 *
 * The snackbar sits above [anchorView] when that is showing, so that it doesn't cover a floating
 * button that lives outside the coordinator layout of this view.
 */
fun View.showActionSnackbar(
    text: CharSequence,
    @StringRes actionRes: Int,
    anchorView: View? = null,
    action: () -> Unit
): Snackbar = Snackbar.make(this, text, Snackbar.LENGTH_LONG)
    .setAction(actionRes) { action() }
    .apply {
        if (anchorView != null && anchorView.isVisible) {
            setAnchorView(anchorView)
        }
        show()
    }
