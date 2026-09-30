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
import java8.nio.file.DirectoryIteratorException
import java8.nio.file.DirectoryStream
import java8.nio.file.FileSystemException
import java8.nio.file.Path
import me.zhanghai.android.files.provider.common.AuthenticationFailedException
import me.zhanghai.android.files.provider.common.PathIteratorDirectoryStream
import me.zhanghai.android.files.provider.common.TestPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A listing fails when SMBJ sends the first QUERY_DIRECTORY (while the iterator is created) or a
 * later one (from `hasNext()`); both have to come out as the provider's own exceptions.
 */
class DirectoryListingFailureTest {
    private class FakeHandle : AutoCloseable {
        var closeCount = 0
        var closeFailure: Exception? = null

        override fun close() {
            closeCount++
            closeFailure?.let { throw it }
        }
    }

    private val signInFailure = SMBApiException(
        NtStatus.STATUS_LOGON_FAILURE.value,
        SMB2MessageCommandCode.SMB2_QUERY_DIRECTORY,
        null
    )

    @Test
    fun theHandleIsLeftOpenWhenTheFirstBatchArrives() {
        val handle = FakeHandle()
        assertEquals("first batch", closeOnSmbFailure(handle) { "first batch" })
        assertEquals(0, handle.closeCount)
    }

    @Test
    fun aFailedFirstBatchClosesTheHandleAndIsAClientException() {
        val handle = FakeHandle()
        val failure = SMBRuntimeException(TransportException("Connection closed"))
        val thrown = assertThrows(ClientException::class.java) {
            closeOnSmbFailure(handle) { throw failure }
        }
        assertSame(failure, thrown.cause)
        assertEquals(1, handle.closeCount)
        // A dead connection on the first batch is one withSession can retry.
        assertTrue(thrown.isConnectionGone)
    }

    @Test
    fun aHandleThatFailsToCloseDoesNotHideTheFailure() {
        val handle = FakeHandle().apply { closeFailure = SMBRuntimeException("Close failed") }
        val failure = signInFailure
        val thrown = assertThrows(ClientException::class.java) {
            closeOnSmbFailure(handle) { throw failure }
        }
        assertSame(failure, thrown.cause)
        assertSame(handle.closeFailure, thrown.suppressed.single())
    }

    @Test
    fun entriesPassThroughUnchanged() {
        val entries = listOf("a", "b").iterator().mapSmbFailures("/share")
        assertEquals(listOf("a", "b"), entries.asSequence().toList())
    }

    @Test
    fun aFailedLaterBatchIsTheProvidersExceptionForTheDirectory() {
        val iterator = failingAfter(1, signInFailure)
            .mapSmbFailures("smb://host/share/folder")
        assertEquals("entry", iterator.next())
        val thrown = assertThrows(DirectoryIteratorException::class.java) { iterator.hasNext() }
        val cause = thrown.cause
        assertTrue(cause.toString(), cause is AuthenticationFailedException)
        assertEquals("smb://host/share/folder", (cause as FileSystemException).file)
        assertTrue(cause.cause is ClientException)
    }

    @Test
    fun nextIsMappedToo() {
        val iterator = object : Iterator<String> {
            override fun hasNext(): Boolean = true

            override fun next(): String = throw SMBRuntimeException("Connection closed")
        }.mapSmbFailures("/share")
        val thrown = assertThrows(DirectoryIteratorException::class.java) { iterator.next() }
        assertEquals("/share", (thrown.cause as FileSystemException).file)
    }

    @Test
    fun otherFailuresAreNotRewrapped() {
        val failure = IllegalStateException("Bug")
        val iterator = failingAfter(0, failure).mapSmbFailures("/share")
        assertSame(failure, assertThrows(IllegalStateException::class.java) { iterator.hasNext() })
    }

    @Test
    fun aDirectoryStreamHandsTheMappedFailureToItsCaller() {
        val paths = failingAfter(1, signInFailure)
            .asSequence()
            .map<String, Path> { TestPath(it) }
            .iterator()
            .mapSmbFailures("/share")
        val stream = PathIteratorDirectoryStream(paths, null, AcceptAll)
        val iterator = stream.iterator()
        assertTrue(iterator.hasNext())
        assertEquals(TestPath("entry"), iterator.next())
        val thrown = assertThrows(DirectoryIteratorException::class.java) { iterator.hasNext() }
        assertTrue(thrown.cause is AuthenticationFailedException)
        stream.close()
    }

    private fun failingAfter(count: Int, failure: RuntimeException): Iterator<String> =
        object : Iterator<String> {
            private var returned = 0

            override fun hasNext(): Boolean {
                if (returned == count) {
                    throw failure
                }
                return true
            }

            override fun next(): String {
                hasNext()
                returned++
                return "entry"
            }
        }

    private object AcceptAll : DirectoryStream.Filter<Path> {
        override fun accept(entry: Path): Boolean = true
    }
}
