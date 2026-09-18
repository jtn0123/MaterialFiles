package me.zhanghai.android.files.app

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class SettingsMigrationTest {
    @Test
    fun failedDestinationDoesNotDeleteOriginal() {
        val source = MemoryPreferences(mutableMapOf("password" to "legacy"))
        val destination = MemoryPreferences(failCommit = true)
        assertThrows(java.io.IOException::class.java) {
            migratePreferences(source.preferences, destination.preferences, setOf("password"))
        }
        assertEquals("legacy", source.values["password"])
    }

    @Test
    fun successCopiesEverySupportedTypeAndRemovesOnlySelectedKeys() {
        val source = MemoryPreferences(
            mutableMapOf(
                "password" to "legacy",
                "port" to 21,
                "other" to true
            )
        )
        val destination = MemoryPreferences()
        migratePreferences(source.preferences, destination.preferences, setOf("password", "port"))
        assertEquals(mapOf("password" to "legacy", "port" to 21), destination.values)
        assertEquals(mapOf("other" to true), source.values)
        migratePreferences(source.preferences, destination.preferences, setOf("password", "port"))
        assertEquals("legacy", destination.values["password"])
    }

    @Test
    fun failedSourceRemovalLeavesDurableDestinationAndCanRetry() {
        val source = MemoryPreferences(mutableMapOf("password" to "legacy"), true)
        val destination = MemoryPreferences()
        assertThrows(java.io.IOException::class.java) {
            migratePreferences(source.preferences, destination.preferences, setOf("password"))
        }
        assertEquals("legacy", destination.values["password"])
        source.failCommit = false
        migratePreferences(source.preferences, destination.preferences, setOf("password"))
        assertFalse(source.values.containsKey("password"))
    }

    @Test fun encryptionFailurePreservesLegacyValue() {
        val store = MemoryPreferences(mutableMapOf("servers" to "legacy"))
        assertThrows(IllegalStateException::class.java) {
            encryptPreference(store.preferences, "servers", {
                false
            }, { error("Keystore unavailable") })
        }
        assertEquals("legacy", store.values["servers"])
    }

    @Test fun encryptedMigrationIsDurableAndIdempotent() {
        val store = MemoryPreferences(mutableMapOf("servers" to "legacy"), true)
        assertThrows(java.io.IOException::class.java) {
            encryptPreference(store.preferences, "servers", {
                it.startsWith("enc:")
            }, { "enc:$it" })
        }
        assertEquals("legacy", store.values["servers"])
        store.failCommit = false
        encryptPreference(store.preferences, "servers", { it.startsWith("enc:") }, { "enc:$it" })
        encryptPreference(store.preferences, "servers", {
            it.startsWith("enc:")
        }, { error("Must not re-encrypt") })
        assertEquals("enc:legacy", store.values["servers"])
    }

    private class MemoryPreferences(
        val values: MutableMap<String, Any> = mutableMapOf(),
        var failCommit: Boolean = false
    ) {
        val preferences: SharedPreferences = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getAll" -> values.toMap()
                "getString" -> values[args!![0]] ?: args[1]
                "edit" -> editor()
                else -> error("Unexpected preferences method: ${method.name}")
            }
        } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            val pending = values.toMutableMap()
            return Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)
            ) { proxy, method, args ->
                when {
                    method.name.startsWith("put") -> {
                        pending[args!![0] as String] = args[1]
                        proxy
                    }

                    method.name == "remove" -> {
                        pending.remove(args!![0] as String)
                        proxy
                    }

                    method.name == "commit" -> {
                        if (!failCommit) {
                            values.clear()
                            values.putAll(pending)
                        }
                        !failCommit
                    }

                    else -> error("Unexpected editor method: ${method.name}")
                }
            } as SharedPreferences.Editor
        }
    }
}
