/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import me.zhanghai.android.files.util.Failure
import me.zhanghai.android.files.util.Loading
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.Success

/** The device has no address on a home or office network, so there is no subnet to search. */
class NotOnLocalNetworkException : IOException("Not connected to a local network")

/** Whether servers can be searched for around [this] address: an IPv4 one on a local network. */
internal val InetAddress?.isSearchableLocalAddress: Boolean
    get() = this is Inet4Address && isSiteLocalAddress

/**
 * What the list of servers found on the local network says about the search when it has no
 * servers to show, or a search that did not finish; `null` while searching or once some are found.
 */
sealed class LanSmbServerDiscoveryStatus {
    /** The search finished and found nothing. */
    object NoneFound : LanSmbServerDiscoveryStatus()

    object NotOnLocalNetwork : LanSmbServerDiscoveryStatus()

    data class Failed(val throwable: Throwable) : LanSmbServerDiscoveryStatus()

    companion object {
        fun of(stateful: Stateful<List<LanSmbServer>>): LanSmbServerDiscoveryStatus? =
            when (stateful) {
                is Loading -> null

                is Success -> if (stateful.value.isEmpty()) NoneFound else null

                is Failure -> if (stateful.throwable is NotOnLocalNetworkException) {
                    NotOnLocalNetwork
                } else {
                    Failed(stateful.throwable)
                }
            }
    }
}
