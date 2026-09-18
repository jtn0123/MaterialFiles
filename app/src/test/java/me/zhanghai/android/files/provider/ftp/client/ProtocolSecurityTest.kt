package me.zhanghai.android.files.provider.ftp.client

import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import org.apache.commons.net.ftp.FTPSClient
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProtocolSecurityTest {
    @Test
    fun secureModesCheckHostnameBeforeConnecting() {
        for (protocol in listOf(Protocol.FTPS, Protocol.FTPES)) {
            val client = protocol.createClient() as FTPSClient
            assertTrue(
                "${protocol.scheme} must verify the endpoint",
                client.isEndpointCheckingEnabled
            )
        }
    }

    @Test
    fun secureModesRejectAnUntrustedButUnexpiredCertificate() {
        val store = KeyStore.getInstance("PKCS12").apply {
            ProtocolSecurityTest::class.java.getResourceAsStream("/tls/server.p12")!!.use {
                load(it, "test-only".toCharArray())
            }
        }
        val certificate = store.getCertificate("server") as X509Certificate
        certificate.checkValidity()
        for (protocol in listOf(Protocol.FTPS, Protocol.FTPES)) {
            val client = protocol.createClient() as FTPSClient
            try {
                (client.trustManager as javax.net.ssl.X509TrustManager)
                    .checkServerTrusted(arrayOf(certificate), "RSA")
                fail("${protocol.scheme} accepted an untrusted certificate")
            } catch (_: CertificateException) {
                // Expected: certificate validity alone is not server authentication.
            }
        }
    }
}
