/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SftpServerHostKeyStoreTest {
    private val host = "test-${System.nanoTime()}.invalid"

    @Test
    fun storesRetrievesAndReplacesKeysPerType() {
        assertTrue(SftpServerHostKeyStore.getHostKeys(host, 22).isEmpty())
        SftpServerHostKeyStore.putHostKey(host, 22, "ssh-ed25519", byteArrayOf(1, 2, 3))
        SftpServerHostKeyStore.putHostKey(host, 22, "ssh-rsa", byteArrayOf(4, 5))
        SftpServerHostKeyStore.putHostKey(host, 2222, "ssh-ed25519", byteArrayOf(9))
        val keys = SftpServerHostKeyStore.getHostKeys(host, 22)
        assertEquals(setOf("ssh-ed25519", "ssh-rsa"), keys.keys)
        assertArrayEquals(byteArrayOf(1, 2, 3), keys["ssh-ed25519"])
        assertArrayEquals(byteArrayOf(4, 5), keys["ssh-rsa"])
        assertArrayEquals(
            byteArrayOf(9),
            SftpServerHostKeyStore.getHostKeys(host, 2222)["ssh-ed25519"]
        )

        SftpServerHostKeyStore.putHostKey(host, 22, "ssh-ed25519", byteArrayOf(7))
        assertArrayEquals(
            byteArrayOf(7),
            SftpServerHostKeyStore.getHostKeys(host, 22)["ssh-ed25519"]
        )
    }
}
