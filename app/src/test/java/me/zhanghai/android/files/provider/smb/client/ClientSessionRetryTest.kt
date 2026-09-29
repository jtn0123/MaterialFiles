/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.smb.client

import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.smbj.common.SMBRuntimeException
import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException
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
        val dropped = mutableListOf<String>()

        fun get(): String = "session${++created}"

        fun evict(session: String) {
            evicted += session
        }

        fun drop(session: String) {
            dropped += session
        }
    }

    private fun connectionLost(cause: Throwable) = ClientException(SMBRuntimeException(cause))

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

    @Test
    fun aDeadConnectionAnywhereInTheCausesIsGone() {
        assertTrue(connectionLost(TransportException("Connection closed")).isConnectionGone)
        assertTrue(connectionLost(TimeoutException("No answer in 60 s")).isConnectionGone)
        assertTrue(
            connectionLost(IOException(SocketException("Connection reset"))).isConnectionGone
        )
        assertTrue(connectionLost(SocketTimeoutException("Read timed out")).isConnectionGone)
        assertTrue(connectionLost(EOFException()).isConnectionGone)
        assertTrue(ClientException(TransportException("Not connected")).isConnectionGone)
    }

    @Test
    fun aServerAnswerOrALocalFailureIsNotADeadConnection() {
        assertFalse(exception(NtStatus.STATUS_ACCESS_DENIED).isConnectionGone)
        assertFalse(exception(NtStatus.STATUS_NETWORK_SESSION_EXPIRED).isConnectionGone)
        assertFalse(ClientException("No password found").isConnectionGone)
        assertFalse(connectionLost(IllegalStateException("Bad state")).isConnectionGone)
        assertFalse(ClientException(SMBRuntimeException("Unknown")).isConnectionGone)
    }

    @Test
    fun anIdempotentOperationRunsOnceMoreOnAFreshConnection() {
        val sessions = Sessions()
        val used = mutableListOf<String>()
        val result = retryWithFreshSession(
            sessions::get,
            sessions::evict,
            isIdempotent = true,
            dropConnection = sessions::drop
        ) {
            used += it
            if (used.size == 1) {
                throw connectionLost(TimeoutException())
            }
            "listed"
        }
        assertEquals("listed", result)
        assertEquals(listOf("session1", "session2"), used)
        assertEquals(listOf("session1"), sessions.dropped)
        assertTrue(sessions.evicted.isEmpty())
    }

    @Test
    fun aDeadConnectionIsRetriedOnlyOnce() {
        val sessions = Sessions()
        var attempts = 0
        assertThrows(ClientException::class.java) {
            retryWithFreshSession(sessions::get, sessions::evict, true, sessions::drop) {
                attempts++
                throw connectionLost(TransportException("Connection closed"))
            }
        }
        assertEquals(2, attempts)
        assertEquals(listOf("session1"), sessions.dropped)
    }

    @Test
    fun anOperationThatMayHaveHappenedIsNotRepeatedOnADeadConnection() {
        val sessions = Sessions()
        var attempts = 0
        val lost = connectionLost(TransportException("Connection closed"))
        val thrown = assertThrows(ClientException::class.java) {
            retryWithFreshSession(sessions::get, sessions::evict, false, sessions::drop) {
                attempts++
                throw lost
            }
        }
        assertSame(lost, thrown)
        assertEquals(1, attempts)
        assertTrue(sessions.dropped.isEmpty())
        assertTrue(sessions.evicted.isEmpty())
    }

    @Test
    fun aGoneSessionIsStillRetriedForAnIdempotentOperationWithoutDroppingTheConnection() {
        val sessions = Sessions()
        var attempts = 0
        retryWithFreshSession(sessions::get, sessions::evict, true, sessions::drop) {
            if (++attempts == 1) {
                throw exception(NtStatus.STATUS_USER_SESSION_DELETED)
            }
        }
        assertEquals(2, attempts)
        assertEquals(listOf("session1"), sessions.evicted)
        assertTrue(sessions.dropped.isEmpty())
    }
}
