/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import android.content.Context
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.zhanghai.android.files.R
import me.zhanghai.android.files.compat.PreferenceManagerCompat
import me.zhanghai.android.files.util.toBase64
import me.zhanghai.android.files.util.use
import me.zhanghai.android.files.util.warningLogger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A stored setting that cannot be read, because it is corrupt or because the key that encrypted it
 * is gone after a restore to another device, reads as its default and is logged, rather than
 * crashing every screen that shows it.
 */
@RunWith(AndroidJUnit4::class)
class CorruptSettingValueTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private val preferences = context.getSharedPreferences(
        "${PreferenceManagerCompat.getDefaultSharedPreferencesName(context)}_$NAME_SUFFIX",
        PreferenceManagerCompat.defaultSharedPreferencesMode
    )

    private val key = "${context.getString(R.string.pref_key_storages)}_$KEY_SUFFIX"

    private val warnings = mutableListOf<Pair<String, String>>()
    private lateinit var defaultWarningLogger: (String, String, Throwable) -> Unit

    @Before
    fun setUp() {
        preferences.edit().clear().commit()
        defaultWarningLogger = warningLogger
        warningLogger = { tag, operation, _ ->
            if (key in operation) {
                synchronized(warnings) { warnings += tag to operation }
            }
        }
    }

    @After
    fun tearDown() {
        warningLogger = defaultWarningLogger
        preferences.edit().clear().commit()
        context.deleteSharedPreferences(
            "${PreferenceManagerCompat.getDefaultSharedPreferencesName(context)}_$NAME_SUFFIX"
        )
    }

    @Test
    fun aCorruptParcelReadsAsTheDefault() {
        val bytes = Parcel.obtain().use { parcel ->
            parcel.writeInt(UNKNOWN_PARCEL_TYPE)
            parcel.marshall()
        }
        preferences.edit().putString(key, bytes.toBase64().value).commit()

        val value = readOnMainThread {
            ParcelValueSettingLiveData(
                NAME_SUFFIX,
                R.string.pref_key_storages,
                KEY_SUFFIX,
                "default"
            )
                .value
        }

        assertEquals("default", value)
        assertEquals(listOf("SettingLiveDatas" to "Read the parcel value for $key"), warnings())
    }

    @Test
    fun anEncryptedParcelThatCannotBeDecryptedReadsAsTheDefault() {
        preferences.edit()
            .putString(key, EncryptedParcelValueSettingLiveData.PREFIX + GARBAGE.toBase64().value)
            .commit()

        val value = readOnMainThread {
            EncryptedParcelValueSettingLiveData(
                NAME_SUFFIX,
                R.string.pref_key_storages,
                KEY_SUFFIX,
                "default"
            ).value
        }

        assertEquals("default", value)
        assertEquals(
            listOf("EncryptedParcelValueSettingLiveData" to "Decode the encrypted parcel for $key"),
            warnings()
        )
    }

    @Test
    fun anEncryptedPasswordThatCannotBeDecryptedIsNeverUsedAsThePassword() {
        preferences.edit().putString("${key}_encrypted_v1", GARBAGE.toBase64().value).commit()

        val value = readOnMainThread {
            EncryptedStringSettingLiveData(
                NAME_SUFFIX,
                R.string.pref_key_storages,
                KEY_SUFFIX,
                R.string.pref_default_value_file_list_view_type
            ).value
        }

        assertEquals(context.getString(R.string.pref_default_value_file_list_view_type), value)
        assertEquals(
            listOf("EncryptedStringSettingLiveData" to "Read the encrypted string for $key"),
            warnings()
        )
    }

    private fun <T> readOnMainThread(read: () -> T): T {
        var value: Result<T>? = null
        // A setting loads its value as it is created, which LiveData allows only on the main
        // thread.
        instrumentation.runOnMainSync { value = runCatching(read) }
        return value!!.getOrThrow()
    }

    private fun warnings(): List<Pair<String, String>> = synchronized(warnings) {
        warnings.toList()
    }

    companion object {
        private const val NAME_SUFFIX = "corrupt_setting_value_test"

        private const val KEY_SUFFIX = "corrupt"

        private const val UNKNOWN_PARCEL_TYPE = 9999

        /** Shorter than the IV that every encrypted value starts with. */
        private val GARBAGE = byteArrayOf(1, 2, 3)
    }
}
