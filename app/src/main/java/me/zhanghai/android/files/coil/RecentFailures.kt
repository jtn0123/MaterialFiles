/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.coil

/**
 * Remembers for a while what could not be done, so that it is not tried again each time a row
 * scrolls back into view. Beyond [maxCount] the least recently asked about is forgotten first.
 */
internal class RecentFailures(
    private val maxCount: Int,
    private val expiryMillis: Long,
    private val clock: () -> Long
) {
    private val failedAt = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean =
            size > maxCount
    }

    @Synchronized
    fun add(key: String) {
        failedAt[key] = clock()
    }

    @Synchronized
    operator fun contains(key: String): Boolean {
        val markedAt = failedAt[key] ?: return false
        if (clock() - markedAt < expiryMillis) {
            return true
        }
        failedAt.remove(key)
        return false
    }
}
