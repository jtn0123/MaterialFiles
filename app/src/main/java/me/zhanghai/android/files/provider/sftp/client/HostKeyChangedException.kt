/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.sftp.client

import android.os.Parcelable
import java.io.IOException
import kotlinx.parcelize.Parcelize

/**
 * The server presented a host key of a type we have a remembered key for, and the two differ. The
 * connection was refused; the user has to decide whether to trust [change].
 */
class HostKeyChangedException(val change: HostKeyChange) :
    IOException(
        "Host key for ${change.host}:${change.port} (${change.keyType}) has changed from " +
            "${change.oldFingerprint} to ${change.newFingerprint}"
    )

/** The [HostKeyChange] behind this error, if a refused host key is what caused it. */
val Throwable.hostKeyChange: HostKeyChange?
    get() = generateSequence(this) { it.cause }
        .filterIsInstance<HostKeyChangedException>()
        .firstOrNull()
        ?.change

@Parcelize
data class HostKeyChange(
    val host: String,
    val port: Int,
    val keyType: String,
    val oldFingerprint: String,
    val newFingerprint: String,
    val newKey: ByteArray
) : Parcelable {
    override fun equals(other: Any?): Boolean =
        other is HostKeyChange && host == other.host && port == other.port &&
            keyType == other.keyType && oldFingerprint == other.oldFingerprint &&
            newFingerprint == other.newFingerprint && newKey.contentEquals(other.newKey)

    override fun hashCode(): Int =
        listOf(host, port, keyType, oldFingerprint, newFingerprint, newKey.contentHashCode())
            .hashCode()
}
