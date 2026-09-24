/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** A failed copy must report why it failed, even when closing the file fails as well. */
class RunThenCloseTest {
    @Test
    fun theResultComesBackAndTheResourceIsClosed() {
        var closeCount = 0
        val result = runThenClose({ ++closeCount }) { "copied" }
        assertEquals("copied", result)
        assertEquals(1, closeCount)
    }

    @Test
    fun aFailedCloseAfterASuccessfulBlockIsThrown() {
        val closeException = IOException("close")
        val written = mutableListOf<String>()
        val thrown = assertThrows(IOException::class.java) {
            runThenClose({ throw closeException }) { written += "data" }
        }
        assertSame(closeException, thrown)
        assertEquals(listOf("data"), written)
    }

    @Test
    fun theBlockFailureWinsAndKeepsTheCloseFailure() {
        val blockException = IOException("write")
        val closeException = IOException("close")
        val thrown = assertThrows(IOException::class.java) {
            runThenClose({ throw closeException }) { throw blockException }
        }
        assertSame(blockException, thrown)
        assertEquals(listOf(closeException), thrown.suppressed.toList())
    }

    @Test
    fun theResourceIsClosedOnceWhenTheBlockFails() {
        var closeCount = 0
        assertThrows(IllegalStateException::class.java) {
            runThenClose({ ++closeCount }) { error("write") }
        }
        assertEquals(1, closeCount)
    }

    @Test
    fun nestedResourcesAreClosedInsideOut() {
        val closed = mutableListOf<String>()
        val thrown = assertThrows(IOException::class.java) {
            runThenClose({ closed += "source" }) {
                runThenClose({ closed += "target" }) { throw IOException("write") }
            }
        }
        assertEquals("write", thrown.message)
        assertEquals(listOf("target", "source"), closed)
        assertTrue(thrown.suppressed.isEmpty())
    }
}
