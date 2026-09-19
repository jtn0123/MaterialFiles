package me.zhanghai.android.files.provider

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import me.zhanghai.android.files.provider.smb.client.Client as SmbClient
import me.zhanghai.android.files.provider.smb.client.getDiskShare
import me.zhanghai.android.files.provider.smb.client.getSession
import me.zhanghai.android.files.provider.webdav.client.AccessTokenAuthentication
import me.zhanghai.android.files.provider.webdav.client.Authenticator
import me.zhanghai.android.files.provider.webdav.client.Authority
import me.zhanghai.android.files.provider.webdav.client.Client
import me.zhanghai.android.files.provider.webdav.client.Protocol
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test

class NetworkProtocolIntegrationTest {
    @Test fun webDavReadsRejectsUnauthorizedAndDetectsTruncatedTransfers() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                if (exchange.requestHeaders.getFirst("Authorization") != "Bearer test-only") {
                    exchange.sendResponseHeaders(401, -1)
                    return@createContext
                }
                when (exchange.requestURI.path) {
                    "/denied" -> exchange.sendResponseHeaders(401, -1)

                    "/truncated" -> {
                        exchange.sendResponseHeaders(200, 100)
                        exchange.responseBody.write("short".toByteArray())
                    }

                    else -> {
                        val body = "hello WebDAV".toByteArray()
                        exchange.sendResponseHeaders(200, body.size.toLong())
                        exchange.responseBody.write(body)
                    }
                }
            } finally {
                exchange.close()
            }
        }
        server.start()
        try {
            val authority = Authority(Protocol.DAV, "127.0.0.1", server.address.port, "test")
            val client = Client(object : Authenticator {
                override fun getAuthentication(authority: Authority) =
                    AccessTokenAuthentication("test-only")
            })
            val path =
                DavPath(authority, "http://127.0.0.1:${server.address.port}/file".toHttpUrl())
            assertEquals("hello WebDAV", client.get(path).bufferedReader().use { it.readText() })
            assertThrows(at.bitfire.dav4jvm.exception.UnauthorizedException::class.java) {
                client.get(path.resolve("/denied")).close()
            }
            assertThrows(IOException::class.java) {
                client.get(path.resolve("/truncated")).use { it.readBytes() }
            }
        } finally {
            server.stop(0)
        }
    }

    @Test fun smbRejectsBadPasswordThenAllowsListingAndRoundTrip() {
        val port = System.getProperty("material.smb.port")
        assumeNotNull("Run tools/network-tests.py to provision the SMB fixture", port)
        var password = "wrong"
        val client = me.zhanghai.android.files.provider.smb.client.Client(
            object : me.zhanghai.android.files.provider.smb.client.Authenticator {
                override fun getPassword(
                    authority: me.zhanghai.android.files.provider.smb.client.Authority
                ) = password
            }
        )
        val authority = me.zhanghai.android.files.provider.smb.client.Authority(
            "127.0.0.1",
            port!!.toInt(),
            "test",
            null
        )
        try {
            assertThrows(
                me.zhanghai.android.files.provider.smb.client.ClientException::class.java
            ) {
                client.getSession(authority)
            }
            password = "test-only"
            val share = client.getDiskShare(client.getSession(authority), "test")
            assertTrue(share.list("").any { it.fileName == "hello.txt" })
            share.openFile(
                "roundtrip.txt",
                setOf(
                    com.hierynomus.msdtyp.AccessMask.GENERIC_READ,
                    com.hierynomus.msdtyp.AccessMask.GENERIC_WRITE
                ),
                java.util.EnumSet.of(com.hierynomus.msfscc.FileAttributes.FILE_ATTRIBUTE_NORMAL),
                com.hierynomus.mssmb2.SMB2ShareAccess.ALL,
                com.hierynomus.mssmb2.SMB2CreateDisposition.FILE_OVERWRITE_IF,
                java.util.EnumSet.noneOf(com.hierynomus.mssmb2.SMB2CreateOptions::class.java)
            ).use { file ->
                val bytes = "SMB round trip".toByteArray()
                file.write(bytes, 0)
                val read = ByteArray(bytes.size)
                assertEquals(bytes.size, file.read(read, 0))
                assertEquals("SMB round trip", String(read))
            }
        } finally {
            client.clientFor(authority).close()
        }
    }

    @Test fun smbStartsOverWhenTheServerForgetsTheTreeAndReadsThroughTheChannel() {
        val port = System.getProperty("material.smb.port")
        val container = System.getProperty("material.smb.container")
        assumeNotNull("Run tools/network-tests.py to provision the SMB fixture", port, container)
        val client = me.zhanghai.android.files.provider.smb.client.Client(
            object : me.zhanghai.android.files.provider.smb.client.Authenticator {
                override fun getPassword(
                    authority: me.zhanghai.android.files.provider.smb.client.Authority
                ) = "test-only"
            }
        )
        val authority = me.zhanghai.android.files.provider.smb.client.Authority(
            "127.0.0.1",
            port!!.toInt(),
            "test",
            null
        )
        val path = SmbPath(authority, SmbClient.Path.SharePath("test", "hello.txt"))
        try {
            client.getPathInformation(path, true)
            val session = client.getSession(authority)
            // The server tears the tree down while the connection and SMBJ's cached tree stay, so
            // the next request through them gets STATUS_NETWORK_NAME_DELETED.
            execInContainer(container!!, "smbcontrol", "smbd", "close-share", "test")
            val deadline = System.nanoTime() + 10_000_000_000
            while (execInContainer(container, "smbstatus", "-S").lines()
                    .any { it.startsWith("test ") }
            ) {
                assertTrue("close-share did not take effect", System.nanoTime() < deadline)
                Thread.sleep(100)
            }
            assertEquals(
                6L,
                client.getPathInformation(path, true).let {
                    (it as me.zhanghai.android.files.provider.smb.client.FileInformation).endOfFile
                }
            )
            assertTrue(client.getSession(authority) !== session)
            val channel = client.openByteChannel(
                path,
                setOf(com.hierynomus.msdtyp.AccessMask.GENERIC_READ),
                java.util.EnumSet.noneOf(com.hierynomus.msfscc.FileAttributes::class.java),
                com.hierynomus.mssmb2.SMB2ShareAccess.ALL,
                com.hierynomus.mssmb2.SMB2CreateDisposition.FILE_OPEN,
                java.util.EnumSet.noneOf(com.hierynomus.mssmb2.SMB2CreateOptions::class.java),
                false
            )
            val content = channel.use { java.nio.channels.Channels.newInputStream(it).readBytes() }
            assertEquals("hello\n", String(content))
        } finally {
            client.clientFor(authority).close()
        }
    }

    private fun execInContainer(container: String, vararg command: String): String {
        val process = ProcessBuilder("docker", "exec", container, *command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(output, 0, process.waitFor())
        return output
    }

    private data class SmbPath(
        override val authority: me.zhanghai.android.files.provider.smb.client.Authority,
        override val sharePath: SmbClient.Path.SharePath?
    ) : SmbClient.Path {
        override fun resolve(other: String): SmbClient.Path = throw UnsupportedOperationException()
    }

    private data class DavPath(override val authority: Authority, override val url: HttpUrl) :
        Client.Path {
        override fun resolve(other: String): Client.Path = DavPath(authority, url.resolve(other)!!)
    }
}
