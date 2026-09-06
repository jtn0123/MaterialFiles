/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import android.content.SharedPreferences
import androidx.annotation.AnyRes
import androidx.annotation.StringRes
import androidx.core.content.edit
import me.zhanghai.android.files.util.CredentialCipher
import me.zhanghai.android.files.util.asBase64
import me.zhanghai.android.files.util.toBase64
import me.zhanghai.android.files.util.toByteArray

/**
 * A [ParcelValueSettingLiveData] whose parcel is encrypted with [CredentialCipher] before it is
 * written. The stored string is [PREFIX] followed by the base64 of the encrypted bytes; a value
 * without the prefix is a plaintext parcel from before encryption, which is still read so that
 * nothing is lost if the migration in AppUpgrader did not run.
 */
class EncryptedParcelValueSettingLiveData<T>(
    nameSuffix: String?,
    @StringRes keyRes: Int,
    keySuffix: String?,
    private val defaultValue: T
) : SettingLiveData<T>(nameSuffix, keyRes, keySuffix, 0) {
    init {
        init()
    }

    override fun getDefaultValue(@AnyRes defaultValueRes: Int): T = defaultValue

    override fun getValue(sharedPreferences: SharedPreferences, key: String, defaultValue: T): T =
        try {
            sharedPreferences.getString(key, null)?.let { decode<T>(it) }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } ?: defaultValue

    override fun putValue(sharedPreferences: SharedPreferences, key: String, value: T) {
        sharedPreferences.edit { putString(key, value?.let { encode(it) }) }
    }

    companion object {
        // Base64 never contains ':', so the prefix cannot collide with a plaintext parcel.
        const val PREFIX = "enc1:"

        fun <T> decode(string: String): T = if (string.startsWith(PREFIX)) {
            val bytes = CredentialCipher.decrypt(
                string.removePrefix(PREFIX).asBase64().toByteArray()
            )
            bytes.toParcelValue()
        } else {
            string.asBase64().toByteArray().toParcelValue()
        }

        fun <T> encode(value: T): String =
            PREFIX + CredentialCipher.encrypt(value.toParcelBytes()).toBase64().value
    }
}
