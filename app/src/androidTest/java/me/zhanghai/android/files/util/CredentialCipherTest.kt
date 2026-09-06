/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.GeneralSecurityException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CredentialCipherTest {
    @Test
    fun roundTripsArbitraryBytes() {
        val plaintext = ByteArray(1000) { it.toByte() }
        assertArrayEquals(plaintext, CredentialCipher.decrypt(CredentialCipher.encrypt(plaintext)))
    }

    @Test
    fun roundTripsEmptyInput() {
        assertEquals(0, CredentialCipher.decrypt(CredentialCipher.encrypt(ByteArray(0))).size)
    }

    @Test
    fun encryptionsOfTheSameInputDiffer() {
        val plaintext = "hunter2".toByteArray()
        assertFalse(
            CredentialCipher.encrypt(plaintext).contentEquals(CredentialCipher.encrypt(plaintext))
        )
    }

    @Test
    fun rejectsTamperedCiphertext() {
        val data = CredentialCipher.encrypt("hunter2".toByteArray())
        data[data.size - 1] = (data[data.size - 1].toInt() xor 1).toByte()
        assertThrows(GeneralSecurityException::class.java) { CredentialCipher.decrypt(data) }
    }

    @Test
    fun rejectsTruncatedData() {
        assertThrows(GeneralSecurityException::class.java) {
            CredentialCipher.decrypt(ByteArray(5))
        }
    }
}
