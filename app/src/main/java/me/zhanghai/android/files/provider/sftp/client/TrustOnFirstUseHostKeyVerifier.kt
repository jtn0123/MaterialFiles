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
 * Trust-on-first-use, like an OpenSSH client with an empty known_hosts: the first key seen for a
 * host is remembered and every later connection must present the same one.
 *
 * A host with remembered keys must present one of them. A key of a type we have nothing stored for
 * is refused like a changed key, because sshj only reorders the host key algorithms it offers: a
 * man in the middle could otherwise offer only `ssh-rsa` against a remembered `ssh-ed25519` and be
 * trusted silently. Once the user trusts it, the new type is remembered alongside the old one.
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
        val storedKeys = store.getHostKeys(hostname, port)
        if (storedKeys.isEmpty()) {
            store.putHostKey(hostname, port, keyType, encodedKey)
            return true
        }
        val storedKey = storedKeys[keyType]
        if (storedKey != null && storedKey.contentEquals(encodedKey)) {
            return true
        }
        val oldFingerprint = if (storedKey != null) {
            storedKey.toSha256Fingerprint()
        } else {
            // Nothing stored for this type: show every remembered key with its type.
            storedKeys.entries.sortedBy { it.key }
                .joinToString("\n") { (type, key) -> "${key.toSha256Fingerprint()} ($type)" }
        }
        hostKeyChangedException = HostKeyChangedException(
            HostKeyChange(
                host,
                this.port,
                keyType,
                oldFingerprint,
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
