/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withCreated
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

/** A field of a server form: the layout that shows its error and the edit that takes the focus. */
class ServerFormField(val layout: TextInputLayout, val edit: TextInputEditText)

/**
 * Shows every error on its field and moves the focus to the first wrong field. Returns whether the
 * form had no errors.
 */
fun ServerFormErrors<ServerFormField>.showOnForm(): Boolean {
    for ((field, error) in errors) {
        field.layout.error = field.layout.context.getString(error)
    }
    errors.firstOrNull()?.first?.edit?.requestFocus()
    return isEmpty
}

/** Makes [toolbar] the action bar of the activity of a server form, with an up button. */
fun Fragment.setUpServerFormToolbar(toolbar: Toolbar, @StringRes titleRes: Int) {
    val activity = requireActivity() as AppCompatActivity
    activity.lifecycleScope.launch {
        activity.withCreated {
            activity.setSupportActionBar(toolbar)
            activity.supportActionBar?.setDisplayHomeAsUpEnabled(true)
            activity.setTitle(titleRes)
        }
    }
}
