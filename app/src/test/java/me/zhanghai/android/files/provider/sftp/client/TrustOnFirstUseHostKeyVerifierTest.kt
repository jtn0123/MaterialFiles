/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.sftp.client

import java.security.PublicKey
import java.util.Base64
import me.zhanghai.android.files.provider.sftp.client.TrustOnFirstUseHostKeyVerifier.Companion.toSha256Fingerprint
import me.zhanghai.android.files.provider.sftp.client.TrustOnFirstUseHostKeyVerifier.Companion.toSshEncoding
import net.schmizz.sshj.common.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustOnFirstUseHostKeyVerifierTest {
    private val store = InMemoryHostKeyStore()
    private val verifier = TrustOnFirstUseHostKeyVerifier(HOST, PORT, store)

    @Test
    fun firstKeyIsRememberedAndAccepted() {
        assertTrue(verifier.verify(HOST, PORT, KEY_1))
        assertArrayEquals(KEY_1.toSshEncoding(), store.getHostKeys(HOST, PORT)["ssh-ed25519"])
        assertNull(verifier.hostKeyChangedException)
    }

    @Test
    fun sameKeyIsAcceptedAgain() {
        assertTrue(verifier.verify(HOST, PORT, KEY_1))
        assertTrue(TrustOnFirstUseHostKeyVerifier(HOST, PORT, store).verify(HOST, PORT, KEY_1))
    }

    @Test
    fun changedKeyIsRefusedWithBothFingerprints() {
        assertTrue(verifier.verify(HOST, PORT, KEY_1))
        val second = TrustOnFirstUseHostKeyVerifier(HOST, PORT, store)
        assertFalse(second.verify(HOST, PORT, KEY_2))
        val change = requireNotNull(second.hostKeyChangedException).change
        assertEquals(HOST, change.host)
        assertEquals(PORT, change.port)
        assertEquals("ssh-ed25519", change.keyType)
        assertEquals(FINGERPRINT_1, change.oldFingerprint)
        assertEquals(FINGERPRINT_2, change.newFingerprint)
        assertArrayEquals(KEY_2.toSshEncoding(), change.newKey)
        // The refused key must not replace the stored one.
        assertArrayEquals(KEY_1.toSshEncoding(), store.getHostKeys(HOST, PORT)["ssh-ed25519"])
    }

    @Test
    fun keysAreKeptPerHostAndPort() {
        assertTrue(verifier.verify(HOST, PORT, KEY_1))
        assertTrue(TrustOnFirstUseHostKeyVerifier(HOST, 2222, store).verify(HOST, 2222, KEY_2))
        assertTrue(
            TrustOnFirstUseHostKeyVerifier("other", PORT, store).verify("other", PORT, KEY_2)
        )
        assertArrayEquals(KEY_1.toSshEncoding(), store.getHostKeys(HOST, PORT)["ssh-ed25519"])
    }

    @Test
    fun existingAlgorithmsListStoredKeyTypes() {
        assertEquals(emptyList<String>(), verifier.findExistingAlgorithms(HOST, PORT))
        verifier.verify(HOST, PORT, KEY_1)
        assertEquals(listOf("ssh-ed25519"), verifier.findExistingAlgorithms(HOST, PORT))
    }

    @Test
    fun fingerprintMatchesOpenSsh() {
        // ssh-keygen -lf on the same keys.
        assertEquals(FINGERPRINT_1, KEY_1.toSshEncoding().toSha256Fingerprint())
        assertEquals(FINGERPRINT_2, KEY_2.toSshEncoding().toSha256Fingerprint())
    }

    private class InMemoryHostKeyStore : HostKeyStore {
        private val keys = mutableMapOf<String, ByteArray>()

        override fun getHostKeys(host: String, port: Int): Map<String, ByteArray> =
            keys.filterKeys { it.startsWith("$host:$port/") }
                .mapKeys { (key, _) -> key.substringAfter('/') }

        override fun putHostKey(host: String, port: Int, keyType: String, key: ByteArray) {
            keys["$host:$port/$keyType"] = key
        }
    }

    companion object {
        private const val HOST = "nas.local"
        private const val PORT = 22

        // Two ssh-ed25519 public keys in their wire encoding (the second field of a .pub file).
        private val KEY_1 = publicKey(
            "AAAAC3NzaC1lZDI1NTE5AAAAIBne9zlTX8snLlaEVhh+tHyVhGRn0xfKrm+N5N+DeX17"
        )
        private val KEY_2 = publicKey(
            "AAAAC3NzaC1lZDI1NTE5AAAAIIccwMVFuixHiyiP7opJO6yfXP9Ui3oflzyDXos13WJr"
        )
        private const val FINGERPRINT_1 = "SHA256:tC0IAOxoNUW+xDvkPZhr/raHLHKYKaTHgxb59dzBNW0"
        private const val FINGERPRINT_2 = "SHA256:JKFmqhDc5OQ9mraQM1LoQBxDq5nfDIPcdlBOkYwHg7c"

        private fun publicKey(base64: String): PublicKey =
            Buffer.PlainBuffer(Base64.getDecoder().decode(base64)).readPublicKey()
    }
}
