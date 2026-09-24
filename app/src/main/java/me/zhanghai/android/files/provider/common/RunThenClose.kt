/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

/**
 * Runs [block] and then [close], like `use` for a resource that is not a [java.io.Closeable] (a
 * raw file descriptor, a remote handle).
 *
 * When [block] throws, a failure in [close] is added to that exception as suppressed, so the
 * original cause is what the caller sees. When [block] succeeds, a failure in [close] is thrown,
 * because a target that failed to close may not hold everything that was written to it.
 */
fun <R> runThenClose(close: () -> Unit, block: () -> R): R {
    val result = try {
        block()
    } catch (t: Throwable) {
        try {
            close()
        } catch (closeException: Throwable) {
            t.addSuppressed(closeException)
        }
        throw t
    }
    close()
    return result
}
