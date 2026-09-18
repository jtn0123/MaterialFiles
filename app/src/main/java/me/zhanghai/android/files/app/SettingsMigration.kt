package me.zhanghai.android.files.app

import android.content.SharedPreferences
import java.io.IOException

internal fun migratePreferences(
    source: SharedPreferences,
    destination: SharedPreferences,
    keys: Set<String>
) {
    val oldValues = source.all.filter { (key, value) ->
        key in keys &&
            (value is String || value is Boolean || value is Int || value is Long || value is Float)
    }
    if (oldValues.isEmpty()) {
        return
    }
    val destinationEditor = destination.edit().apply {
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
    if (!destinationEditor.commit()) throw IOException("Could not save migrated settings")
    if (!source.edit().apply { oldValues.keys.forEach { remove(it) } }.commit()) {
        throw IOException("Could not finish settings migration; originals retained for retry")
    }
}

internal fun encryptPreference(
    preferences: SharedPreferences,
    key: String,
    isEncrypted: (String) -> Boolean,
    encrypt: (String) -> String
) {
    val old = preferences.getString(key, null) ?: return
    if (isEncrypted(old)) return
    val encrypted = encrypt(old)
    if (!preferences.edit().putString(key, encrypted).commit()) {
        throw IOException("Could not persist encrypted server settings")
    }
}
