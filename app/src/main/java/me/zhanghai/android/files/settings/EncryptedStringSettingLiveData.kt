package me.zhanghai.android.files.settings

import android.content.SharedPreferences
import androidx.annotation.StringRes
import me.zhanghai.android.files.app.application

class EncryptedStringSettingLiveData(
    nameSuffix: String?,
    @StringRes keyRes: Int,
    keySuffix: String?,
    @StringRes defaultValueRes: Int
) : SettingLiveData<String>(nameSuffix, keyRes, keySuffix, defaultValueRes) {
    init {
        init()
    }

    override fun getDefaultValue(defaultValueRes: Int): String =
        application.getString(defaultValueRes)

    override fun getValue(
        sharedPreferences: SharedPreferences,
        key: String,
        defaultValue: String
    ): String = try {
        EncryptedPasswordStore.read(sharedPreferences, key, defaultValue)
    } catch (e: Exception) {
        e.printStackTrace()
        // A locked or invalidated key must never turn ciphertext into a login password.
        defaultValue
    }

    override fun putValue(sharedPreferences: SharedPreferences, key: String, value: String) =
        EncryptedPasswordStore.write(sharedPreferences, key, value)

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        super.onSharedPreferenceChanged(sharedPreferences, key?.removeSuffix("_encrypted_v1"))
    }
}
