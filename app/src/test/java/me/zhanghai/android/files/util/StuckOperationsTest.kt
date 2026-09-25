/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StuckOperationsTest {
    private var now = 0L

    private val reported = mutableListOf<StuckOperations.Stuck>()

    private val operations = StuckOperations(60_000, { now }) { reported += it }

    @Test
    fun anOperationThatFinishesInTimeIsNeverReported() {
        operations.track({ "Read" }) { now += 59_999 }
        now += 120_000
        operations.check()
        assertEquals(emptyList<StuckOperations.Stuck>(), reported)
        assertEquals(0, operations.runningCount)
    }

    @Test
    fun anOperationStuckPastTheThresholdIsReportedOnceWithItsThread() {
        val id = operations.start("Read from SMB")
        now = 30_000
        operations.check()
        assertEquals(0, reported.size)
        now = 61_000
        operations.check()
        operations.check()
        val stuck = reported.single()
        assertEquals("Read from SMB", stuck.operation)
        assertSame(Thread.currentThread(), stuck.thread)
        assertEquals(61_000, stuck.runningMillis)
        assertTrue(stuck.message!!, stuck.message!!.contains("61000 ms"))
        operations.finish(id)
        assertEquals(0, operations.runningCount)
    }

    @Test
    fun theReportCarriesTheStuckThreadsStackRatherThanTheCheckers() {
        val isWaiting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val thread = Thread {
            operations.track({ "Wait" }) {
                isWaiting.countDown()
                release.await()
            }
        }
        thread.start()
        isWaiting.await()
        while (thread.state != Thread.State.WAITING) {
            Thread.sleep(1)
        }
        now = 60_000
        operations.check()
        release.countDown()
        thread.join()
        val stackTrace = reported.single().stackTrace
        assertTrue(stackTrace.any { it.methodName == "await" })
        assertTrue(stackTrace.none { it.methodName == "check" })
    }

    @Test
    fun anOperationThatThrowsStopsBeingTracked() {
        assertThrows(IllegalStateException::class.java) {
            operations.track({ "Fail" }) { error("Failed") }
        }
        assertEquals(0, operations.runningCount)
    }
}
