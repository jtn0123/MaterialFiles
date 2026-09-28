/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

/** What [logWarning] was asked to report. */
data class RecordedWarning(val tag: String, val operation: String, val throwable: Throwable)

/**
 * Runs [block] with [warningLogger] collecting the warnings instead of sending them to logcat,
 * which android.jar cannot do on the JVM, and returns them.
 */
fun recordWarnings(block: () -> Unit): List<RecordedWarning> {
    val warnings = mutableListOf<RecordedWarning>()
    val defaultWarningLogger = warningLogger
    warningLogger = { tag, operation, throwable ->
        synchronized(warnings) { warnings += RecordedWarning(tag, operation, throwable) }
    }
    try {
        block()
    } finally {
        warningLogger = defaultWarningLogger
    }
    return synchronized(warnings) { warnings.toList() }
}
