/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.application

/** The text on the clipboard, or null when it is empty. */
val ClipboardManager.primaryText: CharSequence?
    get() = primaryClip?.firstOrNull()?.coerceToText(application)

private const val TOAST_COPIED_TEXT_MAX_LENGTH = 40

fun ClipboardManager.copyText(text: CharSequence, context: Context) {
    setPrimaryClip(ClipData.newPlainText(null, text))
    var copiedText = text
    var ellipsized = false
    if (copiedText.length > TOAST_COPIED_TEXT_MAX_LENGTH) {
        copiedText = copiedText.subSequence(0, TOAST_COPIED_TEXT_MAX_LENGTH)
        ellipsized = true
    }
    val indexOfFirstNewline = copiedText.indexOf('\n')
    if (indexOfFirstNewline != -1) {
        val indexOfSecondNewline = copiedText.indexOf('\n', indexOfFirstNewline + 1)
        if (indexOfSecondNewline != -1) {
            copiedText = copiedText.subSequence(0, indexOfSecondNewline)
            ellipsized = true
        }
    }
    if (ellipsized) {
        copiedText = "$copiedText…"
    }
    context.showToast(context.getString(R.string.copied_to_clipboard_format, copiedText))
}
