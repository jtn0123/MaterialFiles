/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletingCoroutineTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Test
    fun canceledBeforeExecutionStillCompletesOnce() = runBlocking {
        val count = AtomicInteger()
        val job = scope.launchCompleting({ error("Must not run") }) { count.incrementAndGet() }
        job.cancel()
        job.start()
        job.cancelAndJoin()
        assertEquals(1, count.get())
    }

    @Test
    fun failureStillCompletesOnce() = runBlocking {
        val count = AtomicInteger()
        val job = scope.launchCompleting({ throw InterruptedIOException() }) {
            count.incrementAndGet()
        }
        job.start()
        job.join()
        job.cancel()
        assertEquals(1, count.get())
    }

    @Test
    fun aCanceledJobCompletesOnlyAfterItsWorkHasStopped() = runBlocking {
        val started = CountDownLatch(1)
        val workStopped = AtomicBoolean()
        val stoppedWhenCompleted = AtomicBoolean()
        val job = scope.launchCompleting({
            runInterruptible {
                started.countDown()
                try {
                    Thread.sleep(10_000)
                } finally {
                    workStopped.set(true)
                }
            }
        }) { stoppedWhenCompleted.set(workStopped.get()) }
        job.start()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        job.cancelAndJoin()
        assertTrue(stoppedWhenCompleted.get())
    }
}
