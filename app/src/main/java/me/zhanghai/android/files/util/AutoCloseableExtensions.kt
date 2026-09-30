/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

fun AutoCloseable.closeSafe() {
    try {
        close()
    } catch (e: Exception) {
        e.logWarning("AutoCloseableExtensions", "Close $this")
    }
}

/**
 * Runs [block] and closes this, like [use], except that a failure to close is passed through
 * [mapCloseFailure] first (typically to name the path), and never hides a failure of [block].
 */
inline fun <C : AutoCloseable, R> C.useMappingCloseFailure(
    mapCloseFailure: (Exception) -> Exception,
    block: (C) -> R
): R {
    val result = try {
        block(this)
    } catch (t: Throwable) {
        try {
            close()
        } catch (closeFailure: Throwable) {
            t.addSuppressed(closeFailure)
        }
        throw t
    }
    try {
        close()
    } catch (e: Exception) {
        throw mapCloseFailure(e)
    }
    return result
}
