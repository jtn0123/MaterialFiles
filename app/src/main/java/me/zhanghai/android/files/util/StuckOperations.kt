/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Notices blocking work that runs for far longer than it ever should, such as a read from a
 * server that is stuck on a lock rather than on the network, and reports where its thread is.
 * Every read already gives up after its own timeout, so anything still running well past that
 * is a bug rather than a slow server.
 */
class StuckOperations(
    private val thresholdMillis: Long,
    private val clock: () -> Long,
    private val report: (Stuck) -> Unit
) {
    class Stuck(val operation: String, val thread: Thread, val runningMillis: Long) :
        Throwable("$operation has been running for $runningMillis ms on ${thread.name}") {
        init {
            stackTrace = thread.stackTrace
        }

        // The trace is the stuck thread's, set above, not the reporter's.
        override fun fillInStackTrace(): Throwable = this
    }

    private class Entry(val operation: String, val thread: Thread, val startMillis: Long) {
        @Volatile
        var isReported = false
    }

    private val nextId = AtomicLong()

    private val entries = ConcurrentHashMap<Long, Entry>()

    /** The operations running now, for telling what the app was doing when it was put away. */
    val runningCount: Int
        get() = entries.size

    inline fun <T> track(operation: () -> String, block: () -> T): T {
        val id = start(operation())
        try {
            return block()
        } finally {
            finish(id)
        }
    }

    fun start(operation: String): Long {
        val id = nextId.getAndIncrement()
        entries[id] = Entry(operation, Thread.currentThread(), clock())
        return id
    }

    fun finish(id: Long) {
        entries.remove(id)
    }

    /** Reports each operation that has run past the threshold, once. */
    fun check() {
        val now = clock()
        for (entry in entries.values) {
            val runningMillis = now - entry.startMillis
            if (entry.isReported || runningMillis < thresholdMillis) {
                continue
            }
            entry.isReported = true
            report(Stuck(entry.operation, entry.thread, runningMillis))
        }
    }

    companion object {
        /** Well past the 15 s a read on a server waits before giving up. */
        private const val THRESHOLD_MILLIS = 60_000L

        private const val CHECK_INTERVAL_MILLIS = 15_000L

        val instance = StuckOperations(THRESHOLD_MILLIS, System::currentTimeMillis) {
            it.logWarning("StuckOperations", it.message!!)
        }

        private val checker: ScheduledExecutorService by lazy {
            Executors.newSingleThreadScheduledExecutor {
                Thread(it, "StuckOperations").apply { isDaemon = true }
            }
        }

        fun startChecking() {
            checker.scheduleWithFixedDelay(
                { instance.check() },
                CHECK_INTERVAL_MILLIS,
                CHECK_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS
            )
        }
    }
}
