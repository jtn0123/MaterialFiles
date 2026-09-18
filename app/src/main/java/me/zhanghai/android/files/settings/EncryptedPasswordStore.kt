package me.zhanghai.android.files.settings

import android.content.SharedPreferences
import java.io.IOException
import me.zhanghai.android.files.util.CredentialCipher
import me.zhanghai.android.files.util.asBase64
import me.zhanghai.android.files.util.toBase64
import me.zhanghai.android.files.util.toByteArray

internal object EncryptedPasswordStore {
    // A separate key makes every legacy password unambiguous, even if it looks like ciphertext.
    private fun encryptedKey(key: String) = "${key}_encrypted_v1"

    fun read(preferences: SharedPreferences, key: String, fallback: String): String {
        preferences.getString(encryptedKey(key), null)?.let {
            return String(CredentialCipher.decrypt(it.asBase64().toByteArray()), Charsets.UTF_8)
        }
        val legacy = preferences.getString(key, null) ?: return fallback
        write(preferences, key, legacy)
        return legacy
    }

    fun write(preferences: SharedPreferences, key: String, value: String) {
        val encrypted = CredentialCipher.encrypt(value.toByteArray(Charsets.UTF_8)).toBase64().value
        if (!preferences.edit().putString(encryptedKey(key), encrypted).remove(key).commit()) {
            throw IOException("Could not save encrypted password")
        }
    }
}
