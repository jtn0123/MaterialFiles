/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.sftp.client

/**
 * Remembered SSH host keys, keyed by host, port and key type (`ssh-ed25519`, `ssh-rsa`, ...).
 * Keys are stored in their SSH wire encoding.
 */
interface HostKeyStore {
    fun getHostKeys(host: String, port: Int): Map<String, ByteArray>

    fun putHostKey(host: String, port: Int, keyType: String, key: ByteArray)
}
