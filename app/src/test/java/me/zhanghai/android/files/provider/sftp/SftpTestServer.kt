/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.sftp

import me.zhanghai.android.files.provider.sftp.client.Authentication
import me.zhanghai.android.files.provider.sftp.client.Authenticator
import me.zhanghai.android.files.provider.sftp.client.Authority
import me.zhanghai.android.files.provider.sftp.client.Client
import me.zhanghai.android.files.provider.sftp.client.HostKeyStore
import me.zhanghai.android.files.provider.sftp.client.PasswordAuthentication

/** The port `tools/network-tests.py` published for the SSH server, if it is running. */
internal val sftpTestPort: String?
    get() = System.getProperty("material.sftp.port")

/**
 * The file system of [SftpFileSystemProvider] for the SSH server `tools/network-tests.py` runs,
 * whose only user is `test`. The client is shared by every test so that they share the one SSH
 * connection: nothing in the app closes a session before the process ends.
 */
internal fun sftpTestFileSystem(port: Int): SftpFileSystem {
    SftpFileSystemProvider.client = testClient
    return SftpFileSystemProvider.getOrNewFileSystem(Authority("127.0.0.1", port, "test"))
}

private val testClient by lazy {
    Client(
        object : Authenticator {
            override fun getAuthentication(authority: Authority): Authentication =
                PasswordAuthentication("test-only")
        },
        MemoryHostKeyStore()
    )
}

/** Trust on first use, remembered for as long as the tests run. */
private class MemoryHostKeyStore : HostKeyStore {
    private val keys = mutableMapOf<Pair<String, Int>, MutableMap<String, ByteArray>>()

    override fun getHostKeys(host: String, port: Int): Map<String, ByteArray> =
        synchronized(keys) { keys[host to port]?.toMap() ?: emptyMap() }

    override fun putHostKey(host: String, port: Int, keyType: String, key: ByteArray) {
        synchronized(keys) { keys.getOrPut(host to port) { mutableMapOf() }[keyType] = key }
    }
}
