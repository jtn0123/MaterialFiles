/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import androidx.preference.PreferenceDataStore
import me.zhanghai.android.files.util.valueCompat

/**
 * Lets a preference persist through a [SettingLiveData] instead of its preferences file, for
 * settings whose stored form is not the plain value (encrypted ones).
 */
class SettingPreferenceDataStore(private val setting: SettingLiveData<String>) :
    PreferenceDataStore() {
    override fun getString(key: String, defValue: String?): String = setting.valueCompat

    override fun putString(key: String, value: String?) {
        setting.putValue(value ?: "")
    }
}
