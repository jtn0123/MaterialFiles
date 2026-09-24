/*
 * Copyright (c) 2022 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

inline fun <reified T : Throwable> Throwable.findCauseByClass(): T? =
    generateSequence(this) { it.cause }.firstNotNullOfOrNull { it as? T }
