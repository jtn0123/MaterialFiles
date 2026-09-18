/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.app

import android.content.SharedPreferences
import me.zhanghai.android.files.R
import me.zhanghai.android.files.compat.PreferenceManagerCompat
import me.zhanghai.android.files.settings.EncryptedParcelValueSettingLiveData
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.storage.Storage

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
    migratePreferences(defaultSharedPreferences, noBackupSharedPreferences, keys.toSet())
}

internal fun upgradeAppTo1_7_6() {
    encryptStorages1_7_6()
}

/**
 * Stored servers are now encrypted with a keystore key (see [EncryptedParcelValueSettingLiveData]).
 * The reader still understands the plaintext form, so this only rewrites the value once instead of
 * leaving it readable until the user next edits a storage.
 */
private fun encryptStorages1_7_6() {
    val key = application.getString(R.string.pref_key_storages)
    encryptPreference(
        noBackupSharedPreferences,
        key,
        { it.startsWith(EncryptedParcelValueSettingLiveData.PREFIX) },
        { value ->
            val storages = EncryptedParcelValueSettingLiveData.decode<List<Storage>>(value)
            EncryptedParcelValueSettingLiveData.encode(storages)
        }
    )
}

internal val noBackupSharedPreferences: SharedPreferences
    get() {
        val name = "${PreferenceManagerCompat.getDefaultSharedPreferencesName(application)}_${
            Settings.NAME_SUFFIX_NO_BACKUP
        }"
        val mode = PreferenceManagerCompat.defaultSharedPreferencesMode
        return application.getSharedPreferences(name, mode)
    }
