/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.coil

import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunAbortableTest {
    /** Stands for a read in native code: it ignores interruption and only ends when closed. */
    private class UninterruptibleRead : Closeable {
        val started = CountDownLatch(1)
        private val closed = CountDownLatch(1)

        fun read() {
            started.countDown()
            while (true) {
                try {
                    if (closed.await(10, TimeUnit.SECONDS)) {
                        return
                    }
                    error("Never closed")
                } catch (e: InterruptedException) {
                    // Keep reading, as native code would.
                }
            }
        }

        override fun close() {
            closed.countDown()
        }
    }

    @Test
    fun cancellationClosesWhatTheBlockReadsFrom() = runBlocking {
        val read = UninterruptibleRead()
        val deferred = async(Dispatchers.IO) {
            runAbortable { abortHandle ->
                abortHandle.set(read)
                read.read()
            }
        }
        assertTrue(read.started.await(5, TimeUnit.SECONDS))
        // Without the abort this would take the read's full ten seconds and then fail.
        withTimeout(5000) { deferred.cancelAndJoin() }
        assertTrue(deferred.isCancelled)
    }

    @Test
    fun whatIsOpenedAfterCancellationIsClosedRightAway() {
        val abortHandle = AbortHandle()
        abortHandle.close()
        var isClosed = false
        abortHandle.set(Closeable { isClosed = true })
        assertTrue(isClosed)
    }

    @Test
    fun theResultIsReturnedWhenNothingIsCancelled() = runBlocking {
        assertEquals(42, runAbortable { 42 })
    }

    @Test
    fun aFailureInTheBlockIsNotMistakenForCancellation() = runBlocking {
        val result = runCatching { runAbortable<Unit> { throw IllegalStateException("boom") } }
        val exception = result.exceptionOrNull()
        assertTrue(exception is IllegalStateException && exception !is CancellationException)
    }
}
