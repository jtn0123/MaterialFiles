/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import kotlin.reflect.KClass
import me.zhanghai.android.files.app.connectivityManager

fun KClass<InetAddress>.getLocalAddress(): InetAddress? {
    val activeNetwork = connectivityManager.activeNetwork
    if (activeNetwork != null) {
        val linkAddress = connectivityManager.getLinkProperties(activeNetwork)
            ?.linkAddresses
            ?.firstOrNull { it.address.isSiteLocalAddress }
        if (linkAddress != null) {
            return linkAddress.address
        }
    }
    try {
        for (networkInterface in NetworkInterface.getNetworkInterfaces()) {
            if (!networkInterface.isUp || networkInterface.isLoopback) {
                continue
            }
            for (inetAddress in networkInterface.inetAddresses) {
                // Works for consumer IPv4 addresses.
                if (!inetAddress.isSiteLocalAddress) {
                    continue
                }
                return inetAddress
            }
        }
    } catch (e: SocketException) {
        e.printStackTrace()
    }
    return null
}
