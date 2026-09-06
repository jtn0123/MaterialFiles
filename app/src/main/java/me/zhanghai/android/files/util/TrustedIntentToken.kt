/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import android.content.Context
import androidx.core.content.edit
import java.security.SecureRandom
import me.zhanghai.android.files.app.application

/**
 * A per-install secret that marks an intent as built by this app.
 *
 * Several exported activities accept a path in a private extra, which lets the app open archive,
 * remote and root-only paths in its own viewers. Any other app can put the same extra, and
 * Android offers no trustworthy way to learn who started an activity (the referrer is
 * spoofable), so intents carrying the private path extras also carry this token, and the extras
 * are ignored without it. The token lives in app-private storage that other apps cannot read; it
 * is stable across restarts so pinned shortcuts keep working, and it is excluded from backups.
 *
 * It must never be put on an intent that leaves the app (share sheets, "open with" choosers,
 * results returned to other apps): see [Intent.removeTrustedPathExtras].
 */
object TrustedIntentToken {
    private const val PREFERENCES_NAME = "trusted_intent_token"
    private const val KEY_TOKEN = "token"
    private const val TOKEN_BYTES = 16

    val value: String by lazy {
        val preferences = application.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences.getString(KEY_TOKEN, null) ?: generate().also { token ->
            preferences.edit(commit = true) { putString(KEY_TOKEN, token) }
        }
    }

    private fun generate(): String = ByteArray(TOKEN_BYTES)
        .also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }
}
