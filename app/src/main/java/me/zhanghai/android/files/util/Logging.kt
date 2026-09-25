/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import android.util.Log
import androidx.annotation.VisibleForTesting

/**
 * Records an exception the code has decided to survive, so that it reaches logcat with a tag
 * (the class it happened in) and what was being done at the time, typically the operation and
 * the path. This is the app's replacement for `printStackTrace()`, which said neither. It is
 * also kept in [DiagnosticLog], for when logcat was not being watched.
 */
fun Throwable.logWarning(tag: String, operation: String) {
    warningLogger(tag, operation, this)
    DiagnosticLog.append('W', tag, operation, this)
}

/** Where [logWarning] writes to logcat; a JVM test, whose android.jar cannot log, swaps it. */
@VisibleForTesting
var warningLogger: (tag: String, operation: String, throwable: Throwable) -> Unit =
    { tag, operation, throwable -> Log.w(tag, operation, throwable) }
