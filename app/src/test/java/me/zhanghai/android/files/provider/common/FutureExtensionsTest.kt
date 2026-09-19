/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FutureExtensionsTest {
    @Test
    fun deferredFutureTimesOutWhileIncomplete() {
        val future = CompletableDeferred<String>().asFuture()
        assertThrows(TimeoutException::class.java) {
            future.get(10, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun deferredFutureReturnsWhatCompletedInTime() {
        val deferred = CompletableDeferred<String>()
        val future = deferred.asFuture()
        deferred.complete("done")
        assertEquals("done", future.get(10, TimeUnit.MILLISECONDS))
    }
}
