/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ftpserver

import android.os.Bundle
import androidx.preference.EditTextPreference
import me.zhanghai.android.files.R
import me.zhanghai.android.files.compat.PreferenceManagerCompat
import me.zhanghai.android.files.settings.SettingPreferenceDataStore
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.ui.PreferenceFragmentCompat
import me.zhanghai.android.files.util.valueCompat

class FtpServerPreferenceFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferencesFix(savedInstanceState: Bundle?, rootKey: String?) {
        // The FTP server configuration, password included, lives in the preferences file that
        // backup rules exclude; see Settings.NAME_SUFFIX_NO_BACKUP.
        preferenceManager.sharedPreferencesName =
            "${PreferenceManagerCompat.getDefaultSharedPreferencesName(requireContext())}_${
                Settings.NAME_SUFFIX_NO_BACKUP
            }"
        preferenceManager.sharedPreferencesMode =
            PreferenceManagerCompat.defaultSharedPreferencesMode
        addPreferencesFromResource(R.xml.ftp_server)
        // The password is encrypted at rest (Settings.FTP_SERVER_PASSWORD), so the preference
        // cannot persist it as a plain string; it goes through the setting instead. The initial
        // value was already read from the preferences file during inflation, so set it again.
        val passwordPreference =
            findPreference<EditTextPreference>(getString(R.string.pref_key_ftp_server_password))!!
        passwordPreference.preferenceDataStore =
            SettingPreferenceDataStore(Settings.FTP_SERVER_PASSWORD)
        passwordPreference.text = Settings.FTP_SERVER_PASSWORD.valueCompat
    }
}
