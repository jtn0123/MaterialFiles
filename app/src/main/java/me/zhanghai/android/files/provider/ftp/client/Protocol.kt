/*
 * Copyright (c) 2022 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.ftp.client

import java.security.KeyStore
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPSClient

enum class Protocol(val scheme: String, val defaultPort: Int, val createClient: () -> FTPClient) {
    FTP("ftp", FTPClient.DEFAULT_PORT, ::FTPClient),
    FTPS("ftps", FTPSClient.DEFAULT_FTPS_PORT, { createSecureClient(true) }),
    FTPES("ftpes", FTPClient.DEFAULT_PORT, { createSecureClient(false) });

    companion object {
        val SCHEMES = entries.map { it.scheme }

        fun fromScheme(scheme: String): Protocol =
            entries.firstOrNull { it.scheme == scheme } ?: throw IllegalArgumentException(scheme)
    }
}

private fun createSecureClient(implicit: Boolean): FTPSClient = FTPSClient(implicit).apply {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(null as KeyStore?)
    trustManager = factory.trustManagers.filterIsInstance<X509TrustManager>().single()
    isEndpointCheckingEnabled = true
}
