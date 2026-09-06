/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.video

import android.content.SharedPreferences
import java8.nio.file.Path
import me.zhanghai.android.files.app.application

/**
 * Remembers where playback stopped for each video, so that opening it again resumes there.
 *
 * Positions are kept in their own [SharedPreferences] file instead of the default one, so that
 * they never show up in (or get wiped by) the settings, and so that we can prune them without
 * worrying about the rest of the preferences.
 */
object VideoPlaybackPositions {
    private const val PREFERENCE_NAME = "video_playback_positions"

    private val store by lazy {
        VideoPlaybackPositionStore(application.getSharedPreferences(PREFERENCE_NAME, 0))
    }

    fun get(path: Path): Long? = store.get(path.toUri().toString())

    fun set(path: Path, positionMillis: Long, durationMillis: Long) {
        store.set(path.toUri().toString(), positionMillis, durationMillis)
    }

    fun remove(path: Path) {
        store.remove(path.toUri().toString())
    }
}

/**
 * The rules behind [VideoPlaybackPositions], keyed by plain strings so that they can be tested
 * without a file system.
 */
class VideoPlaybackPositionStore(
    private val sharedPreferences: SharedPreferences,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis
) {
    /** Returns the remembered position for [key], or null when there's nothing to resume. */
    fun get(key: String): Long? {
        val value = sharedPreferences.getString(key, null) ?: return null
        return value.substringBefore(SEPARATOR).toLongOrNull()
    }

    fun set(key: String, positionMillis: Long, durationMillis: Long) {
        val isNearBeginning = positionMillis < MINIMUM_POSITION_MILLIS
        val isNearEnd = durationMillis > 0 && positionMillis > durationMillis - END_THRESHOLD_MILLIS
        if (isNearBeginning || isNearEnd) {
            remove(key)
            return
        }
        pruneIfNeeded(key)
        sharedPreferences.edit()
            .putString(key, "$positionMillis$SEPARATOR${currentTimeMillis()}")
            .apply()
    }

    fun remove(key: String) {
        sharedPreferences.edit().remove(key).apply()
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
        sharedPreferences.edit().apply { keysToRemove.forEach { remove(it) } }.apply()
    }

    companion object {
        /** Don't remember a position that is this close to the beginning, it isn't worth resuming. */
        const val MINIMUM_POSITION_MILLIS = 10_000L

        /** Treat a video as finished when this close to the end, and forget its position. */
        const val END_THRESHOLD_MILLIS = 15_000L

        const val ENTRY_COUNT_MAX = 512

        private const val SEPARATOR = ","
    }
}
