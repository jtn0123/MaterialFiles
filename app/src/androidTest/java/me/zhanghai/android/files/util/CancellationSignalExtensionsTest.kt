/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The blocking calls that take a [android.os.CancellationSignal] are run off the coroutine, and
 * cancelling the coroutine has to reach them through the signal.
 */
@RunWith(AndroidJUnit4::class)
class CancellationSignalExtensionsTest {
    @Test
    fun theResultOfTheBlockIsReturned() = runBlocking {
        val result = runWithCancellationSignal { signal ->
            assertFalse("The signal is already cancelled", signal.isCanceled)
            "result"
        }

        assertEquals("result", result)
    }

    @Test
    fun cancellingTheCoroutineCancelsTheSignal() = runBlocking {
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val deferred = async(Dispatchers.Default) {
            runWithCancellationSignal { signal ->
                signal.setOnCancelListener { cancelled.countDown() }
                started.countDown()
                // Long enough that the cancellation below always lands while this is blocked.
                Thread.sleep(5_000)
                "never"
            }
        }
        assertTrue("The block never started", started.await(10, TimeUnit.SECONDS))

        deferred.cancel()

        assertTrue(
            "The cancellation signal was never cancelled",
            cancelled.await(10, TimeUnit.SECONDS)
        )
        try {
            deferred.await()
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            // Expected.
        }
    }
}
