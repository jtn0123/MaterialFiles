/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration
import me.zhanghai.android.files.provider.common.NetworkTimeouts
import me.zhanghai.android.files.provider.ftp.client.Authenticator as FtpAuthenticator
import me.zhanghai.android.files.provider.ftp.client.Authority as FtpAuthority
import me.zhanghai.android.files.provider.ftp.client.Client as FtpClient
import me.zhanghai.android.files.provider.ftp.client.Mode
import me.zhanghai.android.files.provider.ftp.client.Protocol
import me.zhanghai.android.files.provider.ftp.client.applyTimeouts
import me.zhanghai.android.files.provider.sftp.client.Authentication
import me.zhanghai.android.files.provider.sftp.client.Authenticator as SftpAuthenticator
import me.zhanghai.android.files.provider.sftp.client.Authority as SftpAuthority
import me.zhanghai.android.files.provider.sftp.client.Client as SftpClient
import me.zhanghai.android.files.provider.sftp.client.ClientException
import me.zhanghai.android.files.provider.sftp.client.HostKeyStore
import me.zhanghai.android.files.provider.sftp.client.PasswordAuthentication
import net.schmizz.keepalive.KeepAliveRunner
import org.apache.commons.net.ftp.FTPClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A server that accepts the connection and then never says anything (what a half-open connection
 * after sleep or a Wi-Fi change looks like) has to fail within the read timeout, not hang.
 */
class NetworkTimeoutsTest {
    private val server = SilentServer()

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun theDefaultsBoundConnectingReadingAndIdling() {
        val timeouts = NetworkTimeouts()
        assertEquals(15_000, timeouts.connectMillis)
        assertEquals(30_000, timeouts.readMillis)
        assertEquals(30, timeouts.keepAliveSeconds)
    }

    @Test(timeout = 20_000)
    fun sftpGivesUpOnAServerThatNeverAnswers() {
        val client = SftpClient(SftpPasswordAuthenticator, EmptyHostKeyStore, FAST_TIMEOUTS)
        val path = SftpTestPath(SftpAuthority("127.0.0.1", server.port, "user"))
        val elapsedMillis = measureMillis {
            assertThrows(ClientException::class.java) { client.lstat(path) }
        }
        assertTrue(server.acceptedCount > 0)
        assertTrue("Took $elapsedMillis ms", elapsedMillis < GIVE_UP_MILLIS)
    }

    @Test
    fun sftpClientsAreSetUpWithTheTimeouts() {
        val sshClient = SftpClient(SftpPasswordAuthenticator, EmptyHostKeyStore, FAST_TIMEOUTS)
            .newSshClient()
        assertEquals(FAST_TIMEOUTS.connectMillis, sshClient.connectTimeout)
        assertEquals(FAST_TIMEOUTS.readMillis, sshClient.timeout)
        assertEquals(FAST_TIMEOUTS.readMillis, sshClient.transport.timeoutMs)
        val keepAlive = sshClient.connection.keepAlive
        assertEquals(FAST_TIMEOUTS.keepAliveSeconds, keepAlive.keepAliveInterval)
        // A keep-alive the server has to answer, so that a dead connection is noticed.
        assertTrue(keepAlive is KeepAliveRunner)
    }

    // commons-net waits for the greeting as long as the connect timeout, then reads with the
    // read timeout.
    @Test(timeout = 20_000)
    fun ftpGivesUpOnAServerThatNeverGreets() {
        val client = FtpClient(
            object : FtpAuthenticator {
                override fun getPassword(authority: FtpAuthority): String = "password"
            },
            FAST_TIMEOUTS
        )
        val path = FtpTestPath(
            FtpAuthority(Protocol.FTP, "127.0.0.1", server.port, "user", Mode.PASSIVE, "UTF-8")
        )
        val elapsedMillis = measureMillis {
            assertThrows(IOException::class.java) { client.listDirectory(path) }
        }
        assertTrue(server.acceptedCount > 0)
        assertTrue("Took $elapsedMillis ms", elapsedMillis < GIVE_UP_MILLIS)
    }

    @Test
    fun ftpClientsAreSetUpWithTheTimeouts() {
        val client = FTPClient().apply { applyTimeouts(FAST_TIMEOUTS) }
        assertEquals(FAST_TIMEOUTS.connectMillis, client.connectTimeout)
        assertEquals(FAST_TIMEOUTS.readMillis, client.defaultTimeout)
        assertEquals(Duration.ofMillis(FAST_TIMEOUTS.readMillis.toLong()), client.dataTimeout)
        assertEquals(
            Duration.ofSeconds(FAST_TIMEOUTS.keepAliveSeconds.toLong()),
            client.controlKeepAliveTimeoutDuration
        )
    }

    private inline fun measureMillis(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    /** Accepts every connection and keeps it open without ever writing to it. */
    private class SilentServer : AutoCloseable {
        private val serverSocket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        private val sockets = mutableListOf<Socket>()

        val port: Int
            get() = serverSocket.localPort

        val acceptedCount: Int
            get() = synchronized(sockets) { sockets.size }

        init {
            Thread {
                try {
                    while (true) {
                        val socket = serverSocket.accept()
                        synchronized(sockets) { sockets += socket }
                    }
                } catch (_: IOException) {
                    // Closed.
                }
            }.apply { isDaemon = true }.start()
        }

        override fun close() {
            serverSocket.close()
            synchronized(sockets) { sockets.forEach { it.close() } }
        }
    }

    private object SftpPasswordAuthenticator : SftpAuthenticator {
        override fun getAuthentication(authority: SftpAuthority): Authentication =
            PasswordAuthentication("password")
    }

    private object EmptyHostKeyStore : HostKeyStore {
        override fun getHostKeys(host: String, port: Int): Map<String, ByteArray> = emptyMap()

        override fun putHostKey(host: String, port: Int, keyType: String, key: ByteArray): Unit =
            throw AssertionError("A server that never answers has no host key")
    }

    private class SftpTestPath(override val authority: SftpAuthority) : SftpClient.Path {
        override val remotePath: String = "/"

        override fun resolve(other: String): SftpClient.Path = throw UnsupportedOperationException()
    }

    private class FtpTestPath(override val authority: FtpAuthority) : FtpClient.Path {
        override val remotePath: String = "/"

        override fun resolve(other: String): FtpClient.Path = throw UnsupportedOperationException()
    }

    companion object {
        private val FAST_TIMEOUTS =
            NetworkTimeouts(connectMillis = 2_000, readMillis = 500, keepAliveSeconds = 1)

        // Well under anything that would count as hanging, well over the 500 ms read timeout.
        private const val GIVE_UP_MILLIS = 10_000L
    }
}
