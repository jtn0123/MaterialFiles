/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.provider.sftp.client.HostKeyStore
import me.zhanghai.android.files.util.asBase64
import me.zhanghai.android.files.util.toBase64
import me.zhanghai.android.files.util.toByteArray

/**
 * Host keys live in their own preferences file. They are public, so unlike the server
 * credentials they may be backed up: a restored device keeps recognising its servers.
 */
object SftpServerHostKeyStore : HostKeyStore {
    private const val PREFERENCES_NAME = "sftp_host_keys"

    private val preferences: SharedPreferences
        get() = application.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun getHostKeys(host: String, port: Int): Map<String, ByteArray> {
        val prefix = keyPrefix(host, port)
        return preferences.all.mapNotNull { (key, value) ->
            if (key.startsWith(prefix) && value is String) {
                key.removePrefix(prefix) to value.asBase64().toByteArray()
            } else {
                null
            }
        }.toMap()
    }

    override fun putHostKey(host: String, port: Int, keyType: String, key: ByteArray) {
        preferences.edit(commit = true) {
            putString(keyPrefix(host, port) + keyType, key.toBase64().value)
        }
    }

    // Hostnames cannot contain ':' or '/', and key types cannot contain '/'.
    private fun keyPrefix(host: String, port: Int): String = "$host:$port/"
}
