/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import me.zhanghai.android.files.util.fadeToVisibilityUnsafe
import me.zhanghai.android.files.util.toUserMessage
import me.zhanghai.android.files.util.toUserMessageParts

/**
 * The parts of a server form that show connecting to the server: the form stays in sight while it
 * is disabled, so that a failure can point at what to change in it.
 */
class ServerConnectViews(
    private val scrollView: NestedScrollView,
    private val formLayout: ViewGroup,
    private val progress: View,
    private val errorText: TextView,
    private val buttons: List<View>
) {
    fun setConnecting(isConnecting: Boolean) {
        progress.fadeToVisibilityUnsafe(isConnecting)
        formLayout.setDescendantsEnabled(!isConnecting)
        for (button in buttons) {
            button.isEnabled = !isConnecting
        }
        if (isConnecting) {
            errorText.isVisible = false
        }
    }

    /**
     * Shows why connecting failed above the form, and marks [hostField] or [credentialsField]
     * (whichever is showing, if any) when the failure is about it.
     */
    fun showError(
        throwable: Throwable,
        hostField: ServerFormField,
        credentialsField: ServerFormField?
    ) {
        val context = errorText.context
        errorText.text = throwable.toUserMessage(context)
        errorText.isVisible = true
        val field = when (throwable.serverConnectErrorField) {
            ServerConnectErrorField.HOST -> hostField
            ServerConnectErrorField.CREDENTIALS -> credentialsField?.takeIf { it.layout.isShown }
            ServerConnectErrorField.NONE -> null
        }
        val fieldErrorRes = throwable.toUserMessageParts().first
        if (field != null && fieldErrorRes != null) {
            field.layout.error = context.getString(fieldErrorRes)
            field.edit.requestFocus()
        } else {
            scrollView.smoothScrollTo(0, 0)
        }
    }

    private fun ViewGroup.setDescendantsEnabled(enabled: Boolean) {
        for (child in children) {
            child.isEnabled = enabled
            if (child is ViewGroup) {
                child.setDescendantsEnabled(enabled)
            }
        }
    }
}
