package me.zhanghai.android.files.provider.ftp.client

import java.io.ByteArrayInputStream
import java.io.File
import java.net.ServerSocket
import java.security.KeyStore
import javax.net.ssl.TrustManagerFactory
import org.apache.commons.net.ftp.FTPSClient
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.ssl.SslConfigurationFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FtpIntegrationTest {
    @get:Rule val directory = TemporaryFolder()

    private fun withServer(
        protocol: Protocol,
        certificate: String = "server",
        block: (Int) -> Unit
    ) {
        val port = ServerSocket(0).use { it.localPort }
        val factory = FtpServerFactory()
        val listener = ListenerFactory().apply {
            this.port = port
            serverAddress = "127.0.0.1"
            if (protocol != Protocol.FTP) {
                sslConfiguration = SslConfigurationFactory().apply {
                    keystoreFile = File(javaClassResource("/tls/$certificate.p12").toURI())
                    keystorePassword = "test-only"
                    keyPassword = "test-only"
                    keystoreType = "PKCS12"
                }.createSslConfiguration()
                isImplicitSsl = protocol == Protocol.FTPS
            }
        }
        factory.addListener("default", listener.createListener())
        factory.userManager.save(
            BaseUser().apply {
                name = "test"
                password = "test-only"
                homeDirectory = directory.root.absolutePath
                authorities = listOf(WritePermission())
            }
        )
        val server = factory.createServer()
        server.start()
        try {
            block(port)
        } finally {
            server.stop()
        }
    }

    @Test fun ftpProviderListsAndReadsAndRejectsBadCredentials() = withServer(
        Protocol.FTP
    ) { port ->
        File(directory.root, "hello.txt").writeText("hello")
        var password = "wrong"
        val client = Client(object : Authenticator {
            override fun getPassword(authority: Authority) = password
        })
        val authority = Authority(Protocol.FTP, "127.0.0.1", port, "test", Mode.PASSIVE, "UTF-8")
        val path = TestPath(authority, "/")
        assertThrows(java.io.IOException::class.java) { client.listDirectory(path) }
        password = "test-only"
        assertTrue(client.listDirectory(path).any { it.remotePath.endsWith("hello.txt") })
        assertEquals(
            "hello",
            client.retrieveFile(path.resolve("hello.txt")).bufferedReader().use {
                it.readText()
            }
        )
    }

    @Test fun tlsModesTransferOnlyWithTrustedMatchingCertificate() {
        for (protocol in listOf(Protocol.FTPS, Protocol.FTPES)) {
            withServer(protocol) { port ->
                val client = protocol.createClient() as FTPSClient
                client.trustManager = trustedFixture("server")
                client.defaultTimeout = 5000
                try {
                    client.connect("localhost", port)
                    assertTrue(client.login("test", "test-only"))
                    client.execPBSZ(0)
                    client.execPROT("P")
                    client.enterLocalPassiveMode()
                    assertTrue(
                        client.storeFile(
                            "roundtrip.txt",
                            ByteArrayInputStream("round trip".toByteArray())
                        )
                    )
                    assertEquals(
                        "round trip",
                        client.retrieveFileStream("roundtrip.txt").bufferedReader().use {
                            it.readText()
                        }
                    )
                    assertTrue(client.completePendingCommand())
                } finally {
                    if (client.isConnected) client.disconnect()
                }
            }
        }
    }

    @Test fun tlsModesRejectUntrustedAndWrongHostnameAndExpiredCertificates() {
        for (protocol in listOf(Protocol.FTPS, Protocol.FTPES)) {
            for (certificate in listOf("server", "wrong-host", "expired")) {
                withServer(protocol, certificate) { port ->
                    val client = protocol.createClient() as FTPSClient
                    if (certificate != "server") client.trustManager = trustedFixture(certificate)
                    client.defaultTimeout = 5000
                    try {
                        assertThrows(java.io.IOException::class.java) {
                            client.connect("localhost", port)
                        }
                    } finally {
                        if (client.isConnected) client.disconnect()
                    }
                }
            }
        }
    }

    private fun trustedFixture(name: String): javax.net.ssl.X509TrustManager {
        val store = KeyStore.getInstance("PKCS12").apply {
            javaClassResource("/tls/$name.p12").openStream().use {
                load(it, "test-only".toCharArray())
            }
        }
        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("server", store.getCertificate("ca"))
        }
        return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(trust)
        }.trustManagers.filterIsInstance<javax.net.ssl.X509TrustManager>().single()
    }

    private fun javaClassResource(name: String) = FtpIntegrationTest::class.java.getResource(name)!!

    private data class TestPath(
        override val authority: Authority,
        override val remotePath: String
    ) : Client.Path {
        override fun resolve(other: String): Client.Path = TestPath(
            authority,
            remotePath.trimEnd('/') + "/" + other
        )
    }
}
