/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ftpserver

import android.os.Bundle
import me.zhanghai.android.files.R
import me.zhanghai.android.files.compat.PreferenceManagerCompat
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.ui.PreferenceFragmentCompat

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
    }
}
