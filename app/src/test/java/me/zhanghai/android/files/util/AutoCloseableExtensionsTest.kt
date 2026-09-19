/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AutoCloseableExtensionsTest {
    private class Resource(private val closeFailure: Exception? = null) : AutoCloseable {
        var isClosed = false

        override fun close() {
            isClosed = true
            closeFailure?.let { throw it }
        }
    }

    private class Mapped(cause: Exception) : Exception(cause)

    @Test
    fun closesAfterTheBlockAndReturnsItsResult() {
        val resource = Resource()
        val result = resource.useMappingCloseFailure(::Mapped) { 42 }
        assertEquals(42, result)
        assertTrue(resource.isClosed)
    }

    @Test
    fun aFailureToCloseIsMapped() {
        val closeFailure = IOException("close")
        try {
            Resource(closeFailure).useMappingCloseFailure(::Mapped) {}
            fail()
        } catch (e: Mapped) {
            assertSame(closeFailure, e.cause)
        }
    }

    @Test
    fun aFailureToCloseNeverHidesAFailureOfTheBlock() {
        val closeFailure = IOException("close")
        val blockFailure = IOException("block")
        val resource = Resource(closeFailure)
        try {
            resource.useMappingCloseFailure(::Mapped) { throw blockFailure }
            fail()
        } catch (e: IOException) {
            assertSame(blockFailure, e)
            assertSame(closeFailure, e.suppressed.single())
        }
        assertTrue(resource.isClosed)
    }
}
