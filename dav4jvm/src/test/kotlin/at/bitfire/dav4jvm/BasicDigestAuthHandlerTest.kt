/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * SPDX-License-Identifier: MPL-2.0
 */

package at.bitfire.dav4jvm

import okhttp3.Challenge
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response.Builder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BasicDigestAuthHandlerTest {

    @Test
    fun testBasic() {
        var authenticator = BasicDigestAuthHandler(null, "user", "password".toCharArray())
        val original = Request.Builder()
                        .url("http://example.com")
                        .build()
        var response = Builder()
                .request(original)
                .protocol(Protocol.HTTP_1_1)
                .code(401).message("Authentication required")
                .header("WWW-Authenticate", "Basic realm=\"WallyWorld\"")
                .build()
        var request = authenticator.authenticateRequest(original, response)
        assertEquals("Basic dXNlcjpwYXNzd29yZA==", request!!.header("Authorization"))

        // special characters: always use UTF-8 (and don't crash on RFC 7617 charset header)
        authenticator = BasicDigestAuthHandler(null, "username", "paßword".toCharArray())
        response = response.newBuilder()
                .header("WWW-Authenticate", "Basic realm=\"WallyWorld\",charset=UTF-8")
                .build()
        request = authenticator.authenticateRequest(original, response)
        assertEquals("Basic dXNlcm5hbWU6cGHDn3dvcmQ=", request!!.header("Authorization"))
    }

    @Test
    fun testDigestRFCExample() {
        // use cnonce from example
        val authenticator = BasicDigestAuthHandler(null, "Mufasa", "Circle Of Life".toCharArray())
        BasicDigestAuthHandler.clientNonce = "0a4f113b"
        BasicDigestAuthHandler.nonceCount.set(1)

        // construct WWW-Authenticate
        val authScheme = Challenge("Digest", mapOf(
                Pair("realm", "testrealm@host.com"),
                Pair("qop", "auth"),
                Pair("nonce", "dcd98b7102dd2f0e8b11d0f600bfb0c093"),
                Pair("opaque", "5ccc069c403ebaf9f0171e9517f40e41")
        ))

        val original = Request.Builder()
                .get()
                .url("http://www.nowhere.org/dir/index.html")
                .build()
        val request = authenticator.digestRequest(original, authScheme)
        val auth = request!!.header("Authorization")
        assertTrue(auth!!.contains("username=\"Mufasa\""))
        assertTrue(auth.contains("realm=\"testrealm@host.com\""))
        assertTrue(auth.contains("nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\""))
        assertTrue(auth.contains("uri=\"/dir/index.html\""))
        assertTrue(auth.contains("qop=auth"))
        assertTrue(auth.contains("nc=00000001"))
        assertTrue(auth.contains("cnonce=\"0a4f113b\""))
        assertTrue(auth.contains("response=\"6629fae49393a05397450978507c4ef1\""))
        assertTrue(auth.contains("opaque=\"5ccc069c403ebaf9f0171e9517f40e41\""))
    }

    @Test
    fun testDigestRealWorldExamples() {
        var authenticator = BasicDigestAuthHandler(null, "demo", "demo".toCharArray())
        BasicDigestAuthHandler.clientNonce = "MDI0ZDgxYTNmZDk4MTA1ODM0NDNjNmJjNDllYjQ1ZTI="
        BasicDigestAuthHandler.nonceCount.set(1)

        // example 1
        var authScheme = Challenge("Digest", mapOf(
                Pair("realm", "Group-Office"),
                Pair("qop", "auth"),
                Pair("nonce", "56212407212c8"),
                Pair("opaque", "df58bdff8cf60599c939187d0b5c54de")
        ))

        var original = Request.Builder()
                .method("PROPFIND", null)
                .url("https://demo.group-office.eu/caldav/")
                .build()
        var request = authenticator.digestRequest(original, authScheme)
        var auth = request!!.header("Authorization")
        assertTrue(auth!!.contains("username=\"demo\""))
        assertTrue(auth.contains("realm=\"Group-Office\""))
        assertTrue(auth.contains("nonce=\"56212407212c8\""))
        assertTrue(auth.contains("uri=\"/caldav/\""))
        assertTrue(auth.contains("cnonce=\"MDI0ZDgxYTNmZDk4MTA1ODM0NDNjNmJjNDllYjQ1ZTI=\""))
        assertTrue(auth.contains("nc=00000001"))
        assertTrue(auth.contains("qop=auth"))
        assertTrue(auth.contains("response=\"de3b3b194d85ddc62537208c9c3637dc\""))
        assertTrue(auth.contains("opaque=\"df58bdff8cf60599c939187d0b5c54de\""))

        // example 2
        authenticator = BasicDigestAuthHandler(null, "test", "test".toCharArray())
        authScheme = Challenge("digest", mapOf(    // lower case
                Pair("nonce", "87c4c2aceed9abf30dd68c71"),
                Pair("algorithm", "md5"),
                Pair("opaque", "571609eb7058505d35c7bf7288fbbec4-ODdjNGMyYWNlZWQ5YWJmMzBkZDY4YzcxLDAuMC4wLjAsMTQ0NTM3NzE0Nw=="),
                Pair("realm", "ieddy.ru")
        ))
        original = Request.Builder()
                .method("OPTIONS", null)
                .url("https://ieddy.ru/")
                .build()
        request = authenticator.digestRequest(original, authScheme)
        auth = request!!.header("Authorization")
        assertTrue(auth!!.contains("algorithm=\"MD5\""))     // some servers require it
        assertTrue(auth.contains("username=\"test\""))
        assertTrue(auth.contains("realm=\"ieddy.ru\""))
        assertTrue(auth.contains("nonce=\"87c4c2aceed9abf30dd68c71\""))
        assertTrue(auth.contains("uri=\"/\""))
        assertFalse(auth.contains("cnonce="))
        assertFalse(auth.contains("nc=00000001"))
        assertFalse(auth.contains("qop="))
        assertTrue(auth.contains("response=\"d42a39f25f80b0d6907286a960ff9c7d\""))
        assertTrue(auth.contains("opaque=\"571609eb7058505d35c7bf7288fbbec4-ODdjNGMyYWNlZWQ5YWJmMzBkZDY4YzcxLDAuMC4wLjAsMTQ0NTM3NzE0Nw==\""))
    }

    @Test
    fun testDigestMD5Sess() {
        val authenticator = BasicDigestAuthHandler(null, "admin", "12345".toCharArray())
        BasicDigestAuthHandler.clientNonce = "hxk1lu63b6c7vhk"
        BasicDigestAuthHandler.nonceCount.set(1)

        val authScheme = Challenge("Digest", mapOf(
                Pair("realm", "MD5-sess Example"),
                Pair("qop", "auth"),
                Pair("algorithm", "MD5-sess"),
                Pair("nonce", "dcd98b7102dd2f0e8b11d0f600bfb0c093"),
                Pair("opaque", "5ccc069c403ebaf9f0171e9517f40e41")
        ))

        /*  A1 = h("admin:MD5-sess Example:12345"):dcd98b7102dd2f0e8b11d0f600bfb0c093:hxk1lu63b6c7vhk =
                  4eaed818bc587129e73b39c8d3e8425a:dcd98b7102dd2f0e8b11d0f600bfb0c093:hxk1lu63b6c7vhk       a994ee9d33e2f077d3a6e13e882f6686
            A2 = POST:/plain.txt                                                                            1b557703454e1aa1230c5523f54380ed

            h("a994ee9d33e2f077d3a6e13e882f6686:dcd98b7102dd2f0e8b11d0f600bfb0c093:00000001:hxk1lu63b6c7vhk:auth:1b557703454e1aa1230c5523f54380ed") =
            af2a72145775cfd08c36ad2676e89446
        */

        val original = Request.Builder()
                .method("POST", "PLAIN TEXT".toRequestBody("text/plain".toMediaType()))
                .url("http://example.com/plain.txt")
                .build()
        val request = authenticator.digestRequest(original, authScheme)
        val auth = request!!.header("Authorization")
        assertTrue(auth!!.contains("username=\"admin\""))
        assertTrue(auth.contains("realm=\"MD5-sess Example\""))
        assertTrue(auth.contains("nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\""))
        assertTrue(auth.contains("uri=\"/plain.txt\""))
        assertTrue(auth.contains("cnonce=\"hxk1lu63b6c7vhk\""))
        assertTrue(auth.contains("nc=00000001"))
        assertTrue(auth.contains("qop=auth"))
        assertTrue(auth.contains("response=\"af2a72145775cfd08c36ad2676e89446\""))
        assertTrue(auth.contains("opaque=\"5ccc069c403ebaf9f0171e9517f40e41\""))
    }

    @Test
    fun testDigestMD5AuthInt() {
        val authenticator = BasicDigestAuthHandler(null, "admin", "12435".toCharArray())
        BasicDigestAuthHandler.clientNonce = "hxk1lu63b6c7vhk"
        BasicDigestAuthHandler.nonceCount.set(1)

        val authScheme = Challenge("Digest", mapOf(
                Pair("realm", "AuthInt Example"),
                Pair("qop", "auth-int"),
                Pair("nonce", "367sj3265s5"),
                Pair("opaque", "87aaxcval4gba36")
        ))

        /*  A1 = admin:AuthInt Example:12345                            380dc3fc1305127cd2aa81ab68ef3f34

            h("PLAIN TEXT") = 20296edbd4c4275fb416b64e4be752f9
            A2 = POST:/plain.txt:20296edbd4c4275fb416b64e4be752f9       a71c4c86e18b3993ffc98c6e426fe4b0

            h(380dc3fc1305127cd2aa81ab68ef3f34:367sj3265s5:00000001:hxk1lu63b6c7vhk:auth-int:a71c4c86e18b3993ffc98c6e426fe4b0) =
            81d07cb3b8d412b34144164124c970cb
        */

        val original = Request.Builder()
                .method("POST", "PLAIN TEXT".toRequestBody("text/plain".toMediaType()))
                .url("http://example.com/plain.txt")
                .build()
        val request = authenticator.digestRequest(original, authScheme)
        val auth = request!!.header("Authorization")
        assertTrue(auth!!.contains("username=\"admin\""))
        assertTrue(auth.contains("realm=\"AuthInt Example\""))
        assertTrue(auth.contains("nonce=\"367sj3265s5\""))
        assertTrue(auth.contains("uri=\"/plain.txt\""))
        assertTrue(auth.contains("cnonce=\"hxk1lu63b6c7vhk\""))
        assertTrue(auth.contains("nc=00000001"))
        assertTrue(auth.contains("qop=auth-int"))
        assertTrue(auth.contains("response=\"5ab6822b9d906cc711760a7783b28dca\""))
        assertTrue(auth.contains("opaque=\"87aaxcval4gba36\""))
    }

    @Test
    fun testDigestLegacy() {
        val authenticator = BasicDigestAuthHandler(null, "Mufasa", "CircleOfLife".toCharArray())

        // construct WWW-Authenticate
        val authScheme = Challenge("Digest", mapOf(
                Pair("realm", "testrealm@host.com"),
                Pair("nonce", "dcd98b7102dd2f0e8b11d0f600bfb0c093"),
                Pair("opaque", "5ccc069c403ebaf9f0171e9517f40e41")
        ))

        val original = Request.Builder()
                .get()
                .url("http://www.nowhere.org/dir/index.html")
                .build()
        val request = authenticator.digestRequest(original, authScheme)
        val auth = request!!.header("Authorization")
        assertTrue(auth!!.contains("username=\"Mufasa\""))
        assertTrue(auth.contains("realm=\"testrealm@host.com\""))
        assertTrue(auth.contains("nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\""))
        assertTrue(auth.contains("uri=\"/dir/index.html\""))
        assertFalse(auth.contains("qop="))
        assertFalse(auth.contains("nc="))
        assertFalse(auth.contains("cnonce="))
        assertTrue(auth.contains("response=\"1949323746fe6a43ef61f9606e7febea\""))
        assertTrue(auth.contains("opaque=\"5ccc069c403ebaf9f0171e9517f40e41\""))
    }

    @Test
    fun testIncompleteAuthenticationRequests() {
        val authenticator = BasicDigestAuthHandler(null, "demo", "demo".toCharArray())

        val original = Request.Builder()
                .get()
                .url("http://www.nowhere.org/dir/index.html")
                .build()

        assertNull(authenticator.digestRequest(original, Challenge("Digest", mapOf())))

        assertNull(authenticator.digestRequest(original, Challenge("Digest", mapOf(
                Pair("realm", "Group-Office")
        ))))

        assertNull(authenticator.digestRequest(original, Challenge("Digest", mapOf(
                Pair("realm", "Group-Office"),
                Pair("qop", "auth")
        ))))

        assertNotNull(authenticator.digestRequest(original, Challenge("Digest", mapOf(
                Pair("realm", "Group-Office"),
                Pair("qop", "auth"),
                Pair("nonce", "56212407212c8")
        ))))
    }

    @Test
    fun testAuthenticateNull() {
        val authenticator = BasicDigestAuthHandler(null, "demo", "demo".toCharArray())
        // must not crash (route may be null)
        val request = Request.Builder()
                .get()
                .url("http://example.com")
                .build()
        val response = Builder()
                .request(request)
                .protocol(Protocol.HTTP_2)
                .code(200).message("OK")
                .build()
        authenticator.authenticate(null, response)
    }

    private fun unauthorized(request: Request, vararg challenges: String) =
        Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401).message("Unauthorized")
            .apply { for (challenge in challenges) addHeader("WWW-Authenticate", challenge) }
            .build()

    @Test
    fun testDomainRestriction() {
        val authenticator = BasicDigestAuthHandler("example.com", "user", "password".toCharArray())
        val foreign = Request.Builder().url("https://dav.other.org/").build()
        assertNull(authenticator.authenticateRequest(foreign, unauthorized(foreign, "Basic realm=\"x\"")))

        val own = Request.Builder().url("https://dav.example.com/").build()
        assertEquals(
            "Basic dXNlcjpwYXNzd29yZA==",
            authenticator.authenticateRequest(own, unauthorized(own, "Basic realm=\"x\""))!!.header("Authorization")
        )
    }

    @Test
    fun testPreemptiveBasic() {
        val https = Request.Builder().url("https://example.com/").build()
        val http = Request.Builder().url("http://example.com/").build()

        // Plain HTTP: no credentials are sent before the server asks for them.
        assertNull(BasicDigestAuthHandler(null, "user", "password".toCharArray()).authenticateRequest(http, null))

        // HTTPS: Basic credentials are sent right away, and again for the next request.
        val authenticator = BasicDigestAuthHandler(null, "user", "password".toCharArray())
        assertEquals("Basic dXNlcjpwYXNzd29yZA==", authenticator.authenticateRequest(https, null)!!.header("Authorization"))
        assertEquals("Basic dXNlcjpwYXNzd29yZA==", authenticator.authenticateRequest(https, null)!!.header("Authorization"))

        // Plain HTTP when explicitly allowed.
        val insecure = BasicDigestAuthHandler(null, "user", "password".toCharArray(), insecurePreemptive = true)
        assertEquals("Basic dXNlcjpwYXNzd29yZA==", insecure.authenticateRequest(http, null)!!.header("Authorization"))
    }

    @Test
    fun testBasicRejectedTwiceAborts() {
        val authenticator = BasicDigestAuthHandler(null, "user", "password".toCharArray())
        val request = Request.Builder().url("http://example.com/").build()
        val challenge = unauthorized(request, "Basic realm=\"x\"")
        assertNotNull(authenticator.authenticateRequest(request, challenge))
        // The same credentials were rejected: give up instead of looping.
        assertNull(authenticator.authenticateRequest(request, challenge))
        // The cache was cleared, so a new challenge starts over.
        assertNotNull(authenticator.authenticateRequest(request, challenge))
    }

    @Test
    fun testDigestPreferredOverBasicAndStaleNonce() {
        val authenticator = BasicDigestAuthHandler(null, "user", "password".toCharArray())
        val request = Request.Builder().url("http://example.com/dir/").build()
        val first = unauthorized(request, "Basic realm=\"x\"", "Digest realm=\"x\", nonce=\"n1\", qop=\"auth\"")
        val auth = authenticator.authenticateRequest(request, first)!!.header("Authorization")!!
        assertTrue(auth.startsWith("Digest "))
        assertTrue(auth.contains("nonce=\"n1\""))

        // Digest cached: later requests are authenticated without a new challenge.
        assertTrue(authenticator.authenticateRequest(request, null)!!.header("Authorization")!!.startsWith("Digest "))

        // Nonce expired: the server says stale=true, so retry with the new nonce.
        val stale = unauthorized(request, "Digest realm=\"x\", nonce=\"n2\", qop=\"auth\", stale=true")
        assertTrue(authenticator.authenticateRequest(request, stale)!!.header("Authorization")!!.contains("nonce=\"n2\""))

        // Not stale: the credentials themselves are wrong, give up.
        val rejected = unauthorized(request, "Digest realm=\"x\", nonce=\"n3\", qop=\"auth\"")
        assertNull(authenticator.authenticateRequest(request, rejected))
    }

    @Test
    fun testUnsupportedScheme() {
        val authenticator = BasicDigestAuthHandler(null, "user", "password".toCharArray())
        val request = Request.Builder().url("http://example.com/").build()
        assertNull(authenticator.authenticateRequest(request, unauthorized(request, "Bearer realm=\"x\"")))
    }

    @Test
    fun testDigestUnknownAlgorithm() {
        val authenticator = BasicDigestAuthHandler(null, "user", "password".toCharArray())
        val request = Request.Builder().url("http://example.com/").build()
        // With qop: A1 cannot be computed for an unknown algorithm.
        assertNull(authenticator.digestRequest(request, Challenge("Digest", mapOf(
            "realm" to "x", "nonce" to "n", "qop" to "auth", "algorithm" to "SHA-256"
        ))))
        // Legacy (no qop) only knows MD5.
        assertNull(authenticator.digestRequest(request, Challenge("Digest", mapOf(
            "realm" to "x", "nonce" to "n", "algorithm" to "SHA-256"
        ))))
        assertNull(authenticator.digestRequest(request, null))
    }

    @Test
    fun testDigestUnknownQopFallsBackToLegacy() {
        val authenticator = BasicDigestAuthHandler(null, "Mufasa", "CircleOfLife".toCharArray())
        val request = Request.Builder().url("http://www.nowhere.org/dir/index.html").build()
        val auth = authenticator.digestRequest(request, Challenge("Digest", mapOf(
            "realm" to "testrealm@host.com",
            "nonce" to "dcd98b7102dd2f0e8b11d0f600bfb0c093",
            "qop" to "auth-conf"
        )))!!.header("Authorization")!!
        assertFalse(auth.contains("qop="))
        // Same response as the RFC 2069 example in testDigestLegacy.
        assertTrue(auth.contains("response=\"1949323746fe6a43ef61f9606e7febea\""))
    }

    @Test
    fun testDigestAuthIntUnreadableBody() {
        val authenticator = BasicDigestAuthHandler(null, "user", "password".toCharArray())
        val body = object : okhttp3.RequestBody() {
            override fun contentType() = null
            override fun writeTo(sink: okio.BufferedSink): Unit = throw java.io.IOException("gone")
        }
        val request = Request.Builder().url("http://example.com/").put(body).build()
        assertNull(authenticator.digestRequest(request, Challenge("Digest", mapOf(
            "realm" to "x", "nonce" to "n", "qop" to "auth-int"
        ))))
    }

    @Test
    fun testDigestAuthIntWithoutBody() {
        val authenticator = BasicDigestAuthHandler(null, "user", "password".toCharArray())
        val request = Request.Builder().url("http://example.com/").build()
        val auth = authenticator.digestRequest(request, Challenge("Digest", mapOf(
            "realm" to "x", "nonce" to "n", "qop" to "auth,auth-int"
        )))!!.header("Authorization")!!
        // auth-int is preferred when both are offered
        assertTrue(auth.contains("qop=auth-int"))
    }

    @Test
    fun testWithOkHttp() {
        mockwebserver3.MockWebServer().use { server ->
            server.start()
            server.enqueue(mockwebserver3.MockResponse.Builder()
                .code(401)
                .addHeader("WWW-Authenticate", "Basic realm=\"x\"")
                .build())
            server.enqueue(mockwebserver3.MockResponse.Builder().code(200).build())
            server.enqueue(mockwebserver3.MockResponse.Builder().code(200).build())
            server.enqueue(mockwebserver3.MockResponse.Builder().code(200).build())

            val authenticator = BasicDigestAuthHandler(null, "user", "password".toCharArray())
            val client = okhttp3.OkHttpClient.Builder()
                .authenticator(authenticator)
                .addNetworkInterceptor(authenticator)
                .build()

            // First request: no credentials yet; the 401 makes OkHttp ask the authenticator.
            client.newCall(Request.Builder().url(server.url("/a")).build()).execute().use {
                assertEquals(200, it.code)
            }
            assertNull(server.takeRequest().headers["Authorization"])
            assertEquals("Basic dXNlcjpwYXNzd29yZA==", server.takeRequest().headers["Authorization"])

            // Second request: the interceptor adds the cached credentials up front.
            client.newCall(Request.Builder().url(server.url("/b")).build()).execute().close()
            assertEquals("Basic dXNlcjpwYXNzd29yZA==", server.takeRequest().headers["Authorization"])

            // An explicit Authorization header is left alone.
            client.newCall(Request.Builder().url(server.url("/c")).header("Authorization", "Bearer t").build())
                .execute().close()
            assertEquals("Bearer t", server.takeRequest().headers["Authorization"])
        }
    }

}
