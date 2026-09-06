/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM with a key that lives in the Android Keystore, for the stored server credentials.
 *
 * The key never leaves the keystore (hardware-backed where the device has it), so a copy of the
 * app's data directory does not contain what is needed to read the passwords and private keys in
 * it. Nothing more is claimed: a process running as this app can still decrypt.
 */
object CredentialCipher {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "credentials"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12
    private const val TAG_SIZE_BITS = 128

    private val lock = Any()

    /** Returns the 12-byte IV the keystore chose, followed by the ciphertext and tag. */
    @Throws(GeneralSecurityException::class)
    fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        check(cipher.iv.size == IV_SIZE)
        return cipher.iv + cipher.doFinal(plaintext)
    }

    /** The inverse of [encrypt]; fails on any modification of the data. */
    @Throws(GeneralSecurityException::class)
    fun decrypt(data: ByteArray): ByteArray {
        if (data.size < IV_SIZE) {
            throw GeneralSecurityException("Data is too short to contain an IV")
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(TAG_SIZE_BITS, data, 0, IV_SIZE)
        )
        return cipher.doFinal(data, IV_SIZE, data.size - IV_SIZE)
    }

    @Throws(GeneralSecurityException::class)
    private fun getOrCreateKey(): SecretKey {
        synchronized(lock) {
            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as SecretKey?)?.let { return it }
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
                .apply { init(spec) }
                .generateKey()
        }
    }
}
