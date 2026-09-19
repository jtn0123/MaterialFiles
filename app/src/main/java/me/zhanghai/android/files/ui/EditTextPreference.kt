/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ui

import android.content.Context
import android.util.AttributeSet
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes
import androidx.core.content.res.use
import androidx.preference.EditTextPreference as AndroidXEditTextPreference

/**
 * [AndroidXEditTextPreference] that honours `android:inputType` from XML on its dialog's edit
 * text, which the AndroidX one only offers through a listener in code.
 */
open class EditTextPreference : AndroidXEditTextPreference {
    private var inputType = 0
    private var userOnBindEditTextListener: OnBindEditTextListener? = null

    constructor(context: Context) : super(context) {
        init(null)
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init(attrs)
    }

    constructor(context: Context, attrs: AttributeSet?, @AttrRes defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    ) {
        init(attrs)
    }

    constructor(
        context: Context,
        attrs: AttributeSet?,
        @AttrRes defStyleAttr: Int,
        @StyleRes defStyleRes: Int
    ) : super(context, attrs, defStyleAttr, defStyleRes) {
        init(attrs)
    }

    private fun init(attrs: AttributeSet?) {
        context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.inputType)).use {
            inputType = it.getInt(0, 0)
        }
        super.setOnBindEditTextListener { editText ->
            if (inputType != 0) {
                editText.inputType = inputType
            }
            userOnBindEditTextListener?.onBindEditText(editText)
        }
    }

    override fun setOnBindEditTextListener(onBindEditTextListener: OnBindEditTextListener?) {
        userOnBindEditTextListener = onBindEditTextListener
    }
}
