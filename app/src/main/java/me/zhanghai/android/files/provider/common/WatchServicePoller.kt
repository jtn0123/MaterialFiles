/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import java.io.IOException
import java8.nio.file.ClosedWatchServiceException
import java8.nio.file.WatchService
import me.zhanghai.android.files.util.logWarning

/**
 * Waits on [watchService] for the key of a watched path and calls [onChange] when it has events.
 *
 * A key can stop being valid while the path is still being looked at: an SMB server closes an
 * idle connection or drops the handle, a folder is replaced. Instead of going quiet for good, the
 * poller then calls [onChange] once, so that what is shown gets reloaded, and calls [register]
 * again after [INITIAL_RETRY_DELAY_MILLIS], doubling the wait after each failure up to
 * [MAX_RETRY_DELAY_MILLIS], and calls [onChange] again once it succeeds, for what changed in
 * between. A key that stayed valid for [MAX_RETRY_DELAY_MILLIS] starts the next recovery from the
 * shortest wait again.
 *
 * It runs until the thread is interrupted or [watchService] is closed.
 *
 * @param isRegistered whether [register] has already succeeded; if not, the poller starts by
 * trying to register as it would after losing a key.
 */
class WatchServicePoller(
    private val watchService: WatchService,
    private val register: () -> Unit,
    private val onChange: () -> Unit,
    private val isRegistered: Boolean = true,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 }
) : Runnable {
    private var registeredAtMillis = clockMillis()

    private var retryDelayMillis = INITIAL_RETRY_DELAY_MILLIS

    override fun run() {
        if (!isRegistered && !registerAgain()) {
            return
        }
        while (true) {
            val key = try {
                watchService.take()
            } catch (e: ClosedWatchServiceException) {
                return
            } catch (e: InterruptedException) {
                return
            }
            if (key.pollEvents().isNotEmpty()) {
                onChange()
            }
            if (key.reset()) {
                continue
            }
            onChange()
            if (clockMillis() - registeredAtMillis >= MAX_RETRY_DELAY_MILLIS) {
                retryDelayMillis = INITIAL_RETRY_DELAY_MILLIS
            }
            if (!registerAgain()) {
                return
            }
        }
    }

    /** Returns `false` when the poller should stop instead. */
    private fun registerAgain(): Boolean {
        var isFirstFailure = true
        while (true) {
            try {
                sleep(retryDelayMillis)
            } catch (e: InterruptedException) {
                return false
            }
            retryDelayMillis = (retryDelayMillis * 2).coerceAtMost(MAX_RETRY_DELAY_MILLIS)
            try {
                register()
            } catch (e: ClosedWatchServiceException) {
                return false
            } catch (e: IOException) {
                // Once per outage, as it may well last as long as the path is looked at.
                if (isFirstFailure) {
                    e.logWarning("WatchServicePoller", "Watch for changes again")
                    isFirstFailure = false
                }
                continue
            }
            if (Thread.currentThread().isInterrupted) {
                return false
            }
            registeredAtMillis = clockMillis()
            onChange()
            return true
        }
    }

    companion object {
        const val INITIAL_RETRY_DELAY_MILLIS = 5_000L
        const val MAX_RETRY_DELAY_MILLIS = 60_000L
    }
}
