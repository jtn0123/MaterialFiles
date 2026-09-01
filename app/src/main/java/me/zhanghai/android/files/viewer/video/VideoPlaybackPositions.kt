/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.video

import androidx.core.content.edit
import java8.nio.file.Path
import me.zhanghai.android.files.app.application

/**
 * Remembers where playback stopped for each video, so that opening it again resumes there.
 *
 * Positions are kept in their own [android.content.SharedPreferences] file instead of the default
 * one, so that they never show up in (or get wiped by) the settings, and so that we can prune them
 * without worrying about the rest of the preferences.
 */
object VideoPlaybackPositions {
    private const val PREFERENCE_NAME = "video_playback_positions"

    /** Don't remember a position that is this close to the beginning, it isn't worth resuming. */
    private const val MINIMUM_POSITION_MILLIS = 10_000L

    /** Treat a video as finished when this close to the end, and forget its position. */
    private const val END_THRESHOLD_MILLIS = 15_000L

    private const val ENTRY_COUNT_MAX = 512

    private val sharedPreferences by lazy {
        application.getSharedPreferences(PREFERENCE_NAME, 0)
    }

    private fun getKey(path: Path): String = path.toUri().toString()

    /**
     * Returns the remembered position for [path], or
     * [androidx.media3.common.C.TIME_UNSET]-like `null` when there's nothing to resume.
     */
    fun get(path: Path): Long? {
        val value = sharedPreferences.getString(getKey(path), null) ?: return null
        return value.substringBefore(SEPARATOR).toLongOrNull()
    }

    fun set(path: Path, positionMillis: Long, durationMillis: Long) {
        val key = getKey(path)
        val isNearBeginning = positionMillis < MINIMUM_POSITION_MILLIS
        val isNearEnd = durationMillis > 0 && positionMillis > durationMillis - END_THRESHOLD_MILLIS
        if (isNearBeginning || isNearEnd) {
            remove(path)
            return
        }
        pruneIfNeeded(key)
        sharedPreferences.edit {
            putString(key, "$positionMillis$SEPARATOR${System.currentTimeMillis()}")
        }
    }

    fun remove(path: Path) {
        sharedPreferences.edit { remove(getKey(path)) }
    }

    /**
     * Drops the least recently updated entries once we have too many of them, so that this never
     * grows without bound.
     */
    private fun pruneIfNeeded(keyToAdd: String) {
        val all = sharedPreferences.all
        if (all.size < ENTRY_COUNT_MAX || keyToAdd in all) {
            return
        }
        val keysByTime = all.entries
            .sortedBy { (it.value as? String)?.substringAfter(SEPARATOR)?.toLongOrNull() ?: 0 }
            .map { it.key }
        val keysToRemove = keysByTime.take(all.size - ENTRY_COUNT_MAX + 1)
        sharedPreferences.edit { keysToRemove.forEach { remove(it) } }
    }

    private const val SEPARATOR = ","
}
