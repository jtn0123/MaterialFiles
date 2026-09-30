/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Closing is what releases a connection or a file descriptor, so it has to happen even when the
 * work failed, and a failure to close must not hide the failure that came first.
 */
class AutoCloseableExtensionsTest {
    @Test
    fun closeSafeClosesAndSwallowsAFailureToCloseButLogsIt() {
        val closeable = TestCloseable()
        closeable.closeSafe()
        assertTrue(closeable.isClosed)
        val warnings = mutableListOf<Throwable>()
        val defaultWarningLogger = warningLogger
        warningLogger = { _, _, throwable -> warnings += throwable }
        try {
            TestCloseable(failToClose = true).closeSafe()
        } finally {
            warningLogger = defaultWarningLogger
        }
        assertEquals("close failed", warnings.single().message)
    }

    @Test
    fun theResultOfTheWorkIsReturnedAndTheCloseableIsClosed() {
        val closeable = TestCloseable()
        val result = closeable.useMappingCloseFailure({ it }) { "done with $it" }
        assertEquals("done with $closeable", result)
        assertTrue(closeable.isClosed)
    }

    @Test
    fun aFailureToCloseIsReportedAsTheCallerWantsItNamed() {
        val closeable = TestCloseable(failToClose = true)
        val exception = assertThrows(IOException::class.java) {
            closeable.useMappingCloseFailure({ IOException("/path/to/file", it) }) {}
        }
        assertEquals("/path/to/file", exception.message)
        assertEquals("close failed", exception.cause!!.message)
    }

    @Test
    fun aFailureOfTheWorkIsPassedThroughUnchangedAndStillCloses() {
        val closeable = TestCloseable()
        val failure = IllegalStateException("work failed")
        val thrown = assertThrows(IllegalStateException::class.java) {
            closeable.useMappingCloseFailure({ IOException("mapped", it) }) { throw failure }
        }
        assertSame(failure, thrown)
        assertTrue(closeable.isClosed)
    }

    @Test
    fun aFailureToCloseAfterAFailedWorkIsOnlySuppressed() {
        val failure = IllegalStateException("work failed")
        val thrown = assertThrows(IllegalStateException::class.java) {
            TestCloseable(failToClose = true)
                .useMappingCloseFailure({ IOException("mapped", it) }) { throw failure }
        }
        assertSame(failure, thrown)
        assertEquals(1, thrown.suppressed.size)
        assertEquals("close failed", thrown.suppressed[0].message)
    }

    private class TestCloseable(private val failToClose: Boolean = false) : AutoCloseable {
        var isClosed = false
            private set

        override fun close() {
            isClosed = true
            if (failToClose) {
                throw IOException("close failed")
            }
        }
    }
}
