/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

/**
 * How long a client of a remote file system waits on the network. Without these a connection that
 * died silently (the phone slept or changed Wi-Fi, a NAT forgot us) leaves a listing or a read
 * waiting forever, and cancelling the listing cannot interrupt a blocked socket read.
 *
 * @param connectMillis how long establishing the TCP connection may take.
 * @param readMillis how long a single read may wait for the server to send something.
 * @param keepAliveSeconds how often an idle connection is probed, so that a dead one is noticed.
 */
data class NetworkTimeouts(
    val connectMillis: Int = 15_000,
    val readMillis: Int = 30_000,
    val keepAliveSeconds: Int = 30
)
