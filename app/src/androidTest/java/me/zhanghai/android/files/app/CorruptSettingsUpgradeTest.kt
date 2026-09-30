/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.app

import android.content.SharedPreferences
import android.os.Parcel
import androidx.annotation.StringRes
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.zhanghai.android.files.R
import me.zhanghai.android.files.util.toBase64
import me.zhanghai.android.files.util.use
import me.zhanghai.android.files.util.warningLogger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A setting saved by an old version that the upgrade cannot make sense of, say one written by a
 * build that never shipped, is dropped and logged: the app starts with the default rather than
 * crashing on every launch.
 */
@RunWith(AndroidJUnit4::class)
class CorruptSettingsUpgradeTest {
    private val keys = listOf(
        R.string.pref_key_file_list_default_directory,
        R.string.pref_key_file_list_sort_options,
        R.string.pref_key_create_archive_type,
        R.string.pref_key_standard_directory_settings,
        R.string.pref_key_bookmark_directories,
        R.string.pref_key_ftp_server_home_directory,
        R.string.pref_key_storages,
        R.string.pref_key_root_strategy
    ).map { application.getString(it) }

    private lateinit var savedSettings: Map<String, Any?>
    private lateinit var savedPathSettings: Map<String, *>

    private val warnings = mutableListOf<String>()
    private lateinit var defaultWarningLogger: (String, String, Throwable) -> Unit

    @Before
    fun setUp() {
        savedSettings = keys.associateWith { defaultSharedPreferences.all[it] }
        savedPathSettings = pathSharedPreferences.all.toMap()
        // Upgrading to 1.1.0 rewrites every per-directory setting, which this test is not about.
        pathSharedPreferences.edit().clear().commit()
        defaultSharedPreferences.edit().apply { keys.forEach { remove(it) } }.commit()
        defaultWarningLogger = warningLogger
        warningLogger = { tag, operation, _ ->
            if (tag == "AppUpgraders" || tag == "AppUpgradersFrom140") {
                synchronized(warnings) { warnings += operation }
            }
        }
    }

    @After
    fun tearDown() {
        warningLogger = defaultWarningLogger
        defaultSharedPreferences.edit().apply {
            for ((key, value) in savedSettings) {
                putValue(key, value)
            }
        }.commit()
        pathSharedPreferences.edit().apply {
            for ((key, value) in savedPathSettings) {
                putValue(key, value)
            }
        }.commit()
    }

    @Test
    fun corruptSettingsFromBefore1_1_0AreDropped() {
        // Each value starts with what the setting itself reads as nothing or as an unknown type,
        // so that it falls back to its default while the value is there.
        putParcel(R.string.pref_key_file_list_default_directory) { writeInt(PARCEL_VAL_NULL) }
        putParcel(R.string.pref_key_file_list_sort_options) {
            writeInt(PARCEL_VAL_NULL)
            writeInt(99)
        }
        putParcel(R.string.pref_key_bookmark_directories) {
            writeInt(UNKNOWN_PARCEL_TYPE)
            writeInt(0)
            writeLong(1)
            writeString("Bookmark")
            writeString("not.a.Path")
        }

        upgradeAppTo1_1_0()

        assertDropped(R.string.pref_key_file_list_default_directory)
        assertDropped(R.string.pref_key_file_list_sort_options)
        assertDropped(R.string.pref_key_bookmark_directories)
        assertWarned("Migrate the path setting ", "to 1.1.0")
        assertWarned("Migrate the file sort options setting ", "to 1.1.0")
        assertWarned("Migrate the bookmark directories setting ", "to 1.1.0")
    }

    @Test
    fun corruptServersFrom1_3_0To1_7_2AreDropped() {
        putUnknownStorage()
        upgradeAppTo1_3_0()
        assertDropped(R.string.pref_key_storages)
        assertWarned("Migrate the SMB servers setting ", "to 1.3.0")

        putParcel(R.string.pref_key_storages) {
            writeInt(0)
            writeInt(1)
            writeInt(UNKNOWN_PARCEL_TYPE)
            writeString("not.a.Storage")
        }
        upgradeAppTo1_5_0()
        assertDropped(R.string.pref_key_storages)
        assertWarned("Migrate the SFTP servers setting ", "to 1.5.0")

        putParcel(R.string.pref_key_storages) {
            writeInt(0)
            // The length of the list, which is prefixed since Android 13.
            writeInt(0)
            writeInt(1)
            writeInt(UNKNOWN_PARCEL_TYPE)
            writeInt(0)
            writeString("not.a.Storage")
        }
        upgradeAppTo1_7_2()
        assertDropped(R.string.pref_key_storages)
        assertWarned("Migrate the document manager shortcut setting ", "to 1.7.2")
    }

    @Test
    fun corruptSettingsFrom1_4_0AreDropped() {
        putParcel(R.string.pref_key_file_list_default_directory) {
            writeInt(PARCEL_VAL_NULL)
            writeString("not.a.Path")
        }
        putParcel(R.string.pref_key_storages) {
            writeInt(0)
            writeInt(1)
            writeInt(UNKNOWN_PARCEL_TYPE)
            writeString("not.a.Storage")
        }
        putParcel(R.string.pref_key_bookmark_directories) {
            writeInt(UNKNOWN_PARCEL_TYPE)
            writeInt(1)
            writeInt(0)
            writeString("Bookmark")
            writeLong(1)
            writeString("Bookmark")
            writeString("not.a.Path")
        }

        upgradeAppTo1_4_0()

        assertDropped(R.string.pref_key_file_list_default_directory)
        assertDropped(R.string.pref_key_storages)
        assertDropped(R.string.pref_key_bookmark_directories)
        assertWarned("Migrate the path setting ", "to 1.4.0")
        assertWarned("Migrate the SFTP servers setting ", "to 1.4.0")
        assertWarned("Migrate the bookmark directories setting ", "to 1.4.0")
    }

    /** A list of one storage whose type no version of Parcel knows. */
    private fun putUnknownStorage() {
        putParcel(R.string.pref_key_storages) {
            writeInt(1)
            writeInt(UNKNOWN_PARCEL_TYPE)
            writeString("not.a.Storage")
        }
    }

    private fun putParcel(@StringRes keyRes: Int, write: Parcel.() -> Unit) {
        val bytes = Parcel.obtain().use { parcel ->
            parcel.write()
            parcel.marshall()
        }
        defaultSharedPreferences.edit()
            .putString(application.getString(keyRes), bytes.toBase64().value)
            .commit()
    }

    private fun assertDropped(@StringRes keyRes: Int) {
        val key = application.getString(keyRes)
        assertFalse("$key was kept", defaultSharedPreferences.contains(key))
    }

    private fun assertWarned(prefix: String, suffix: String) {
        val matching = synchronized(warnings) {
            warnings.filter { it.startsWith(prefix) && it.endsWith(suffix) }
        }
        assertEquals("$prefix... $suffix in $warnings", 1, matching.size)
    }

    private fun SharedPreferences.Editor.putValue(key: String, value: Any?) {
        @Suppress("UNCHECKED_CAST")
        when (value) {
            null -> remove(key)
            is String -> putString(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is Boolean -> putBoolean(key, value)
            is Set<*> -> putStringSet(key, value as Set<String>)
            else -> error("Unexpected setting $key = $value")
        }
    }

    companion object {
        private const val PARCEL_VAL_NULL = -1

        private const val UNKNOWN_PARCEL_TYPE = 9999
    }
}
