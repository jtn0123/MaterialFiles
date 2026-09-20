/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.ftp

import java.io.File
import java.net.ServerSocket
import me.zhanghai.android.files.provider.ftp.client.Authenticator
import me.zhanghai.android.files.provider.ftp.client.Authority
import me.zhanghai.android.files.provider.ftp.client.Client
import me.zhanghai.android.files.provider.ftp.client.Mode
import me.zhanghai.android.files.provider.ftp.client.Protocol
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission

/**
 * Runs a real FTP server over [root] on the loopback interface and hands [block] the file system
 * of [FtpFileSystemProvider] for it, so that provider code is exercised against a server that
 * answers like the ones users have.
 */
internal fun withFtpFileSystem(root: File, block: (FtpFileSystem) -> Unit) {
    val port = ServerSocket(0).use { it.localPort }
    val factory = FtpServerFactory()
    factory.addListener(
        "default",
        ListenerFactory()
            .apply {
                this.port = port
                serverAddress = "127.0.0.1"
            }
            .createListener()
    )
    factory.userManager.save(
        BaseUser().apply {
            name = "test"
            password = "test-only"
            homeDirectory = root.absolutePath
            authorities = listOf(WritePermission())
        }
    )
    val server = factory.createServer()
    server.start()
    try {
        FtpFileSystemProvider.client = Client(
            object : Authenticator {
                override fun getPassword(authority: Authority) = "test-only"
            }
        )
        val authority =
            Authority(Protocol.FTP, "127.0.0.1", port, "test", Mode.PASSIVE, "UTF-8")
        val fileSystem = FtpFileSystemProvider.getOrNewFileSystem(authority)
        try {
            block(fileSystem)
        } finally {
            fileSystem.close()
        }
    } finally {
        server.stop()
    }
}
