/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.sftp.client

import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.HostKeyVerifier

/**
 * Trust-on-first-use, like an OpenSSH client with an empty known_hosts: the first key of a given
 * type seen for a host is remembered and every later connection must present the same one.
 *
 * sshj only lets a verifier say yes or no, so a mismatch is recorded in [hostKeyChangedException]
 * for [Client] to surface with the fingerprints.
 */
class TrustOnFirstUseHostKeyVerifier(
    private val host: String,
    private val port: Int,
    private val store: HostKeyStore
) : HostKeyVerifier {
    @Volatile
    var hostKeyChangedException: HostKeyChangedException? = null
        private set

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
        store.getHostKeys(hostname, port).keys.toList()

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val keyType = KeyType.fromKey(key).toString()
        val encodedKey = key.toSshEncoding()
        val storedKey = store.getHostKeys(hostname, port)[keyType]
        if (storedKey == null) {
            store.putHostKey(hostname, port, keyType, encodedKey)
            return true
        }
        if (storedKey.contentEquals(encodedKey)) {
            return true
        }
        hostKeyChangedException = HostKeyChangedException(
            HostKeyChange(
                host,
                this.port,
                keyType,
                storedKey.toSha256Fingerprint(),
                encodedKey.toSha256Fingerprint(),
                encodedKey
            )
        )
        return false
    }

    companion object {
        fun PublicKey.toSshEncoding(): ByteArray =
            Buffer.PlainBuffer().putPublicKey(this).compactData

        /** OpenSSH's `SHA256:` fingerprint format (unpadded base64 of the key's SHA-256). */
        fun ByteArray.toSha256Fingerprint(): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(this)
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
        }
    }
}
