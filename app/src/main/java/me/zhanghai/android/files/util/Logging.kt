/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import android.util.Log

/**
 * Records an exception the code has decided to survive, so that it reaches logcat with a tag
 * (the class it happened in) and what was being done at the time, typically the operation and
 * the path. This is the app's replacement for `printStackTrace()`, which said neither.
 */
fun Throwable.logWarning(tag: String, operation: String) {
    Log.w(tag, operation, this)
}
