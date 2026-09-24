/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.smb

import me.zhanghai.android.files.provider.smb.client.Authority
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cancelling an [SmbWatchKey] whose notifier thread has already removed itself, which it does when
 * the connection drops: the key can still be valid when [SmbWatchKey.cancel] checks it, so the
 * service must accept a key it no longer has a notifier for.
 */
class SmbWatchServiceTest {
    private val authority = Authority("server", Authority.DEFAULT_PORT, "user", null)

    private val fileSystem = SmbFileSystemProvider.getOrNewFileSystem(authority)

    private val watchService = SmbWatchService()

    @After
    fun tearDown() {
        watchService.close()
        fileSystem.close()
    }

    @Test
    fun cancellingAValidKeyWhoseNotifierIsAlreadyGoneDoesNothing() {
        // The state between the key seeing itself valid and the service looking up its notifier,
        // when the notifier thread has meanwhile removed itself.
        val key = SmbWatchKey(watchService, fileSystem.getPath("/share/dir"))
        assertTrue(key.isValid)
        key.cancel()
        watchService.cancel(key)
        assertNull(watchService.poll())
    }

    @Test
    fun cancellingAKeyTheNotifierHasInvalidatedDoesNothing() {
        val key = SmbWatchKey(watchService, fileSystem.getPath("/share/dir"))
        key.setInvalid()
        key.cancel()
        assertFalse(key.isValid)
        assertNull(watchService.poll())
    }
}
