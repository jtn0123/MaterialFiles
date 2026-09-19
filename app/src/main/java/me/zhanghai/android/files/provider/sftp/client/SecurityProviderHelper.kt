/*
 * Copyright (c) 2021 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.sftp.client

import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider

// @see https://android-developers.googleblog.com/2018/03/cryptography-changes-in-android-p.html
// @see net.schmizz.sshj.common.SecurityUtils
// @see net.schmizz.sshj.DefaultConfig.DefaultConfig
/**
 * SSHJ requires the full Bouncy Castle to be registered as a security provider before most of
 * its functionality works, so it replaces the platform's stripped-down one. Constructing and
 * registering the provider costs most of a second on the main thread, so it is done once, on
 * demand, by [ensureInitialized]: the file system provider kicks it off on a worker at startup
 * and the client makes sure of it before its first connection.
 */
object SecurityProviderHelper {
    private val initialization = lazy {
        val bouncyCastleProvider = BouncyCastleProvider()
        Security.removeProvider(bouncyCastleProvider.name)
        Security.addProvider(bouncyCastleProvider)
    }

    fun ensureInitialized() {
        initialization.value
    }
}
