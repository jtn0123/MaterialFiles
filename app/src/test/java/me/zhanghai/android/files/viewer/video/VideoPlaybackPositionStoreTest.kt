/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.video

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlaybackPositionStoreTest {
    private val preferences = FakeSharedPreferences()
    private var now = 1_000L
    private val store = VideoPlaybackPositionStore(preferences) { now }

    @Test
    fun rememberedPositionRoundTrips() {
        store.set("a", 30_000, 120_000)
        assertEquals(30_000L, store.get("a"))
        assertNull(store.get("b"))
    }

    @Test
    fun positionNearTheBeginningIsNotKept() {
        store.set("a", 30_000, 120_000)
        store.set("a", VideoPlaybackPositionStore.MINIMUM_POSITION_MILLIS - 1, 120_000)
        assertNull(store.get("a"))
    }

    @Test
    fun positionAtTheMinimumIsKept() {
        store.set("a", VideoPlaybackPositionStore.MINIMUM_POSITION_MILLIS, 120_000)
        assertEquals(VideoPlaybackPositionStore.MINIMUM_POSITION_MILLIS, store.get("a"))
    }

    @Test
    fun positionNearTheEndIsNotKept() {
        store.set("a", 30_000, 120_000)
        store.set("a", 120_000 - VideoPlaybackPositionStore.END_THRESHOLD_MILLIS + 1, 120_000)
        assertNull(store.get("a"))
    }

    @Test
    fun unknownDurationOnlyAppliesTheBeginningRule() {
        store.set("a", 30_000, -1)
        assertEquals(30_000L, store.get("a"))
        store.set("a", 5_000, -1)
        assertNull(store.get("a"))
    }

    @Test
    fun removeForgetsThePosition() {
        store.set("a", 30_000, 120_000)
        store.remove("a")
        assertNull(store.get("a"))
        assertFalse(preferences.contains("a"))
    }

    @Test
    fun leastRecentlyUpdatedEntriesArePrunedFirst() {
        val max = VideoPlaybackPositionStore.ENTRY_COUNT_MAX
        for (i in 0 until max) {
            now = i.toLong()
            store.set("key$i", 30_000, 120_000)
        }
        assertEquals(max, preferences.all.size)
        // Updating an existing key never prunes.
        now = 10_000
        store.set("key0", 40_000, 120_000)
        assertEquals(max, preferences.all.size)
        assertEquals(40_000L, store.get("key0"))
        // Adding one more drops the oldest, which is now key1 because key0 was just refreshed.
        now = 10_001
        store.set("new", 30_000, 120_000)
        assertEquals(max, preferences.all.size)
        assertNull(store.get("key1"))
        assertTrue(preferences.contains("key0"))
        assertTrue(preferences.contains("new"))
    }

    /** Just enough of [SharedPreferences] for the store: string values, in memory. */
    private class FakeSharedPreferences : SharedPreferences {
        private val map = mutableMapOf<String, Any?>()

        override fun getAll(): Map<String, *> = map.toMap()
        override fun getString(key: String, defValue: String?): String? =
            map[key] as String? ?: defValue
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? {
            @Suppress("UNCHECKED_CAST")
            val value = map[key] as Set<String>?
            return value ?: defValues
        }
        override fun getInt(key: String, defValue: Int): Int = map[key] as Int? ?: defValue
        override fun getLong(key: String, defValue: Long): Long = map[key] as Long? ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = map[key] as Float? ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            map[key] as Boolean? ?: defValue
        override fun contains(key: String): Boolean = key in map
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener
        ) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val puts = mutableMapOf<String, Any?>()
            private val removes = mutableSetOf<String>()
            private var clear = false

            override fun putString(key: String, value: String?) = apply { puts[key] = value }
            override fun putStringSet(key: String, values: Set<String>?) =
                apply { puts[key] = values }
            override fun putInt(key: String, value: Int) = apply { puts[key] = value }
            override fun putLong(key: String, value: Long) = apply { puts[key] = value }
            override fun putFloat(key: String, value: Float) = apply { puts[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { puts[key] = value }
            override fun remove(key: String) = apply { removes += key }
            override fun clear() = apply { clear = true }
            override fun commit(): Boolean {
                if (clear) map.clear()
                removes.forEach { map.remove(it) }
                puts.forEach { (key, value) ->
                    if (value == null) map.remove(key) else map[key] = value
                }
                return true
            }
            override fun apply() {
                commit()
            }
        }
    }
}
