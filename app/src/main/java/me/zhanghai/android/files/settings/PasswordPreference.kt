/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import android.content.Context
import android.text.method.PasswordTransformationMethod
import android.util.AttributeSet
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes
import androidx.preference.EditTextPreference as AndroidXEditTextPreference
import me.zhanghai.android.files.ui.EditTextPreference
import me.zhanghai.android.files.util.logWarning

class PasswordPreference : EditTextPreference {
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    init {
        if (summaryProvider is AndroidXEditTextPreference.SimpleSummaryProvider) {
            summaryProvider = SimpleSummaryProvider
        }
    }

    override fun getPersistedString(defaultReturnValue: String?): String? = if (shouldPersist()) {
        try {
            EncryptedPasswordStore.read(sharedPreferences!!, key, defaultReturnValue.orEmpty())
        } catch (e: Exception) {
            e.logWarning("PasswordPreference", "Read the persisted password for $key")
            defaultReturnValue
        }
    } else {
        defaultReturnValue
    }

    override fun persistString(value: String?): Boolean {
        if (!shouldPersist()) return false
        return try {
            EncryptedPasswordStore.write(sharedPreferences!!, key, value.orEmpty())
            true
        } catch (e: Exception) {
            e.logWarning("PasswordPreference", "Persist the password for $key")
            android.widget.Toast.makeText(
                context,
                me.zhanghai.android.files.R.string.password_save_failed,
                android.widget.Toast.LENGTH_LONG
            ).show()
            false
        }
    }

    companion object {
        /** Shows the password as dots, and the usual "not set" summary when there is none. */
        val SimpleSummaryProvider = SummaryProvider<EditTextPreference> { preference ->
            val text = preference.text
            if (!text.isNullOrEmpty()) {
                PasswordTransformationMethod.getInstance().getTransformation(text, null)
            } else {
                AndroidXEditTextPreference.SimpleSummaryProvider.getInstance()
                    .provideSummary(preference)
            }
        }
    }
}
