package me.zhanghai.android.files.settings

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EncryptedPasswordStoreTest {
    @Test fun migratesLegacyPasswordAndNeverStoresItInPlaintextAgain() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences(
            "password-migration-test",
            Context.MODE_PRIVATE
        )
        try {
            preferences.edit().clear().putString("password", "enc1:legacy-password").commit()
            assertEquals(
                "enc1:legacy-password",
                EncryptedPasswordStore.read(preferences, "password", "")
            )
            assertFalse(preferences.contains("password"))
            assertFalse(preferences.all.values.any { it.toString().contains("legacy-password") })
            EncryptedPasswordStore.write(preferences, "password", "replacement")
            assertEquals("replacement", EncryptedPasswordStore.read(preferences, "password", ""))
        } finally {
            preferences.edit().clear().commit()
        }
    }
}
