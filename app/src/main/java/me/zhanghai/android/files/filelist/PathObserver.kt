/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java8.nio.file.Path
import me.zhanghai.android.files.provider.common.PathObservable
import me.zhanghai.android.files.provider.common.observe
import me.zhanghai.android.files.util.backgroundExecutor
import me.zhanghai.android.files.util.closeSafe
import me.zhanghai.android.files.util.logWarning

/**
 * Observes [path] for changes, starting on a background thread.
 *
 * A change made before the observer is in place is never reported, so whatever reads what it
 * observes should call [awaitObserving] first.
 */
class PathObserver(
    path: Path,
    observe: (Path) -> PathObservable = { it.observe(THROTTLE_INTERVAL_MILLIS) },
    private val executor: Executor = backgroundExecutor,
    postToMain: (() -> Unit) -> Unit = { mainHandler.post(it) },
    @MainThread onChange: () -> Unit
) : Closeable {
    private var pathObservable: PathObservable? = null

    private var closed = false
    private val lock = Any()

    private val started = CountDownLatch(1)

    private val startLock = Any()

    // Guarded by startLock.
    private var isStarted = false

    // Guarded by startLock.
    private var isChangeOwedWhenStarted = false

    init {
        executor.execute {
            var isObserving = false
            try {
                synchronized(lock) {
                    if (closed) {
                        return@execute
                    }
                    pathObservable = try {
                        observe(path)
                    } catch (e: UnsupportedOperationException) {
                        // Ignored.
                        return@execute
                    } catch (e: IOException) {
                        e.logWarning("PathObserver", "Observe $path for changes")
                        return@execute
                    }.apply { addObserver { postToMain(onChange) } }
                    isObserving = true
                }
            } finally {
                val isChangeOwed = synchronized(startLock) {
                    isStarted = true
                    isChangeOwedWhenStarted
                }
                started.countDown()
                if (isObserving && isChangeOwed) {
                    postToMain(onChange)
                }
            }
        }
    }

    /**
     * Waits until the observer is in place, or could not be, so that what is read afterwards
     * cannot miss a change. If that takes longer than [timeoutMillis], returns `false` instead and
     * reports a change once the observer is in place, for what the caller then read too early.
     */
    @Throws(InterruptedException::class)
    fun awaitObserving(timeoutMillis: Long): Boolean {
        if (started.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
            return true
        }
        synchronized(startLock) {
            if (isStarted) {
                return true
            }
            isChangeOwedWhenStarted = true
        }
        return false
    }

    override fun close() {
        executor.execute {
            synchronized(lock) {
                if (closed) {
                    return@execute
                }
                closed = true
                pathObservable?.closeSafe()
            }
        }
    }

    companion object {
        private const val THROTTLE_INTERVAL_MILLIS = 1000L

        private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    }
}
