/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import java8.nio.file.StandardWatchEventKinds
import java8.nio.file.WatchEvent

/**
 * Checks the arguments of a `register()` call the way every watch service here does: create,
 * delete and modify are kept, overflow is dropped since a key receives it without asking, and any
 * other kind or any modifier is unsupported.
 */
@Throws(UnsupportedOperationException::class)
fun watchEventKindSetOf(
    kinds: Array<WatchEvent.Kind<*>>,
    modifiers: Array<out WatchEvent.Modifier>
): Set<WatchEvent.Kind<*>> {
    val kindSet = kinds.filterTo(mutableSetOf()) { kind ->
        when (kind) {
            StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE,
            StandardWatchEventKinds.ENTRY_MODIFY -> true

            StandardWatchEventKinds.OVERFLOW -> false

            else -> throw UnsupportedOperationException(kind.name())
        }
    }
    modifiers.firstOrNull()?.let { throw UnsupportedOperationException(it.name()) }
    return kindSet
}
