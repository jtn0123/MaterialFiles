/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.app

import android.content.SharedPreferences
import androidx.core.content.edit
import me.zhanghai.android.files.R
import me.zhanghai.android.files.compat.PreferenceManagerCompat
import me.zhanghai.android.files.settings.Settings

internal fun upgradeAppTo1_7_5() {
    migrateNoBackupSettings1_7_5()
}

/**
 * Stored servers (with their passwords and keys) and the FTP server configuration moved to a
 * preferences file that backup rules exclude, so that neither cloud backup nor `adb backup`
 * carries credentials. See [Settings.NAME_SUFFIX_NO_BACKUP].
 */
private fun migrateNoBackupSettings1_7_5() {
    val keys = listOf(
        R.string.pref_key_storages,
        R.string.pref_key_ftp_server_anonymous_login,
        R.string.pref_key_ftp_server_username,
        R.string.pref_key_ftp_server_password,
        R.string.pref_key_ftp_server_port,
        R.string.pref_key_ftp_server_home_directory,
        R.string.pref_key_ftp_server_writable
    ).map { application.getString(it) }
    val oldValues = defaultSharedPreferences.all.filterKeys { it in keys }
    if (oldValues.isEmpty()) {
        return
    }
    noBackupSharedPreferences.edit(commit = true) {
        for ((key, value) in oldValues) {
            when (value) {
                is String -> putString(key, value)

                is Boolean -> putBoolean(key, value)

                is Int -> putInt(key, value)

                is Long -> putLong(key, value)

                is Float -> putFloat(key, value)

                else -> {
                    // A string set is the only other type SharedPreferences stores, and none
                    // of these keys is one; leave it behind rather than guess.
                }
            }
        }
    }
    defaultSharedPreferences.edit(commit = true) { oldValues.keys.forEach { remove(it) } }
}

internal val noBackupSharedPreferences: SharedPreferences
    get() {
        val name = "${PreferenceManagerCompat.getDefaultSharedPreferencesName(application)}_${
            Settings.NAME_SUFFIX_NO_BACKUP
        }"
        val mode = PreferenceManagerCompat.defaultSharedPreferencesMode
        return application.getSharedPreferences(name, mode)
    }
