/*
 * Copyright (c) 2023 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ftpserver

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Handler
import android.os.Looper
import java.net.InetAddress
import me.zhanghai.android.files.compat.getSystemServiceCompat
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.getLocalAddress
import me.zhanghai.android.files.util.valueCompat

object FtpServerUrl {
    fun getUrl(): String? {
        val localAddress = InetAddress::class.getLocalAddress() ?: return null
        val username = if (!Settings.FTP_SERVER_ANONYMOUS_LOGIN.valueCompat) {
            Settings.FTP_SERVER_USERNAME.valueCompat
        } else {
            null
        }
        val host = localAddress.hostAddress
        val port = Settings.FTP_SERVER_PORT.valueCompat
        return "ftp://${if (username != null) "$username@" else ""}$host:$port/"
    }

    fun createChangeWatcher(context: Context, onChange: () -> Unit): ChangeWatcher =
        ChangeWatcher(context.getSystemServiceCompat(ConnectivityManager::class.java), onChange)

    /**
     * Calls [onChange] on the main thread whenever the default network comes, goes or changes its
     * addresses, so that a displayed URL can be refreshed.
     */
    class ChangeWatcher(
        private val connectivityManager: ConnectivityManager,
        onChange: () -> Unit
    ) {
        internal val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onChange()
            }

            override fun onLost(network: Network) {
                onChange()
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                onChange()
            }
        }

        fun register() {
            connectivityManager.registerDefaultNetworkCallback(
                networkCallback,
                Handler(Looper.getMainLooper())
            )
        }

        fun unregister() {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        }
    }
}
