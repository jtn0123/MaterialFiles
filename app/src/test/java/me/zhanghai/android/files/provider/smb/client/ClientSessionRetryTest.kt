/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.smb.client

import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMBApiException
import java8.nio.file.AccessDeniedException
import java8.nio.file.NoSuchFileException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientSessionRetryTest {
    private class Sessions {
        var created = 0
        val evicted = mutableListOf<String>()

        fun get(): String = "session${++created}"

        fun evict(session: String) {
            evicted += session
        }
    }

    private fun exception(status: NtStatus) =
        ClientException(SMBApiException(status.value, SMB2MessageCommandCode.SMB2_CREATE, null))

    @Test
    fun theSessionsThatTheServerForgotAreGone() {
        assertTrue(exception(NtStatus.STATUS_USER_SESSION_DELETED).isSessionGone)
        assertTrue(exception(NtStatus.STATUS_NETWORK_SESSION_EXPIRED).isSessionGone)
        assertTrue(exception(NtStatus.STATUS_NETWORK_NAME_DELETED).isSessionGone)
        assertFalse(exception(NtStatus.STATUS_ACCESS_DENIED).isSessionGone)
        assertFalse(exception(NtStatus.STATUS_OBJECT_NAME_NOT_FOUND).isSessionGone)
        assertFalse(ClientException("No password found").isSessionGone)
    }

    @Test
    fun aGoneSessionIsNeitherAccessDeniedNorNoSuchFile() {
        for (status in listOf(
            NtStatus.STATUS_USER_SESSION_DELETED,
            NtStatus.STATUS_NETWORK_SESSION_EXPIRED,
            NtStatus.STATUS_NETWORK_NAME_DELETED
        )) {
            val fileSystemException = exception(status).toFileSystemException("/file")
            assertFalse(status.name, fileSystemException is AccessDeniedException)
            assertFalse(status.name, fileSystemException is NoSuchFileException)
        }
    }

    @Test
    fun aGoneSessionIsEvictedAndTheOperationRunsOnceMoreWithAFreshOne() {
        val sessions = Sessions()
        val used = mutableListOf<String>()
        val result = retryWithFreshSession(sessions::get, sessions::evict) {
            used += it
            if (used.size == 1) {
                throw exception(NtStatus.STATUS_NETWORK_SESSION_EXPIRED)
            }
            "done"
        }
        assertEquals("done", result)
        assertEquals(listOf("session1", "session2"), used)
        assertEquals(listOf("session1"), sessions.evicted)
    }

    @Test
    fun theRetryHappensOnlyOnce() {
        val sessions = Sessions()
        var attempts = 0
        assertThrows(ClientException::class.java) {
            retryWithFreshSession(sessions::get, sessions::evict) {
                attempts++
                throw exception(NtStatus.STATUS_USER_SESSION_DELETED)
            }
        }
        assertEquals(2, attempts)
        assertEquals(listOf("session1"), sessions.evicted)
    }

    @Test
    fun otherFailuresAreNotRetriedAndKeepTheSession() {
        val sessions = Sessions()
        var attempts = 0
        val denied = exception(NtStatus.STATUS_ACCESS_DENIED)
        val thrown = assertThrows(ClientException::class.java) {
            retryWithFreshSession(sessions::get, sessions::evict) {
                attempts++
                throw denied
            }
        }
        assertSame(denied, thrown)
        assertEquals(1, attempts)
        assertTrue(sessions.evicted.isEmpty())
    }
}
