/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.sftp.client

import java.security.KeyPairGenerator
import java.util.Base64
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.method.AuthPublickey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticationTest {
    @Test
    fun aPasswordBecomesThePasswordAuthMethod() {
        val method = PasswordAuthentication("test-only").toAuthMethod()
        assertTrue(method is AuthPassword)
        assertEquals("password", method.name)
    }

    @Test
    fun aPrivateKeyBecomesThePublicKeyAuthMethod() {
        val method = PublicKeyAuthentication(privateKeyPem(), null).toAuthMethod()
        assertTrue(method is AuthPublickey)
        assertEquals("publickey", method.name)
    }

    @Test
    fun validateAcceptsAKeyItCanRead() {
        assertNull(PublicKeyAuthentication.validate(privateKeyPem(), null))
    }

    @Test
    fun validateReportsWhatIsWrongWithAKeyItCannotRead() {
        val notAKey = PublicKeyAuthentication.validate("not a private key at all", null)
        assertNotNull(notAKey)
        // A key of an unknown format is reported rather than thrown at the caller.
        val truncated = PublicKeyAuthentication.validate(
            privateKeyPem().lineSequence().take(2).joinToString("\n"),
            null
        )
        assertNotNull(truncated)
    }

    /** An unencrypted PKCS#8 private key, the format `ssh-keygen` writes with `-m PKCS8`. */
    private fun privateKeyPem(): String {
        val keyPair = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()
        val base64 = Base64.getMimeEncoder(64, "\n".toByteArray())
            .encodeToString(keyPair.private.encoded)
        return "-----BEGIN PRIVATE KEY-----\n$base64\n-----END PRIVATE KEY-----\n"
    }
}
