/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The app's pool for blocking I/O off the main thread: directory listings, file metadata, path
 * observers, LAN discovery. It grows with demand and retires idle threads, so one slow remote
 * listing cannot starve the others the way the CPU-sized, framework-shared
 * `AsyncTask.THREAD_POOL_EXECUTOR` could.
 */
val backgroundExecutor: ExecutorService by lazy {
    val threadNumber = AtomicInteger()
    Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "background-${threadNumber.incrementAndGet()}").apply {
            isDaemon = true
        }
    }
}
