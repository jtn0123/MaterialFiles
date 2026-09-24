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

import okhttp3.Authenticator
import okhttp3.Challenge
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.Route
import okio.Buffer
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.util.LinkedList
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger

/**
 * Handler to manage authentication against a given service (may be limited to one domain).
 * There's no domain-based cache, because the same user name and password will be used for
 * all requests.
 *
 * Authentication methods/credentials found to be working will be cached for further requests
 * (this is why the interceptor is needed).
 *
 * Usage: Set as authenticator *and* as network interceptor.
 */
class BasicDigestAuthHandler(
    /** Authenticate only against hosts ending with this domain (may be null, which means no restriction) */
    val domain: String?,

    val username: String,
    val password: CharArray,

    val insecurePreemptive: Boolean = false
): Authenticator, Interceptor {

    companion object {
        private const val HEADER_AUTHORIZATION = "Authorization"

        // cached digest parameters
        var clientNonce = h(UUID.randomUUID().toString())
        var nonceCount = AtomicInteger(1)

        fun quotedString(s: String) = "\"" + s.replace("\"", "\\\"") + "\""
        fun h(data: String) = data.toByteArray().toByteString().md5().hex()

        fun h(body: RequestBody): String {
            val buffer = Buffer()
            body.writeTo(buffer)
            return buffer.readByteArray().toByteString().md5().hex()
        }

        fun kd(secret: String, data: String) = h("$secret:$data")
    }

    // cached authentication schemes
    private var basicAuth: Challenge? = null
    private var digestAuth: Challenge? = null
    
    private val logger = Logger.getLogger(javaClass.name)


    fun authenticateRequest(request: Request, response: Response?): Request? {
        if (!isAllowedHost(request.url.host))
            return null

        if (response == null) {
            // we're not processing a 401 response
            if (basicAuth == null && digestAuth == null && (request.isHttps || insecurePreemptive)) {
                logger.fine("Trying Basic auth preemptively")
                basicAuth = Challenge("Basic", "")
            }
        } else if (!updateChallenges(response)) {
            // we're processing a 401 response, and the cached credentials were rejected
            return null
        }

        // we MUST prefer Digest auth [https://tools.ietf.org/html/rfc2617#section-4.6]
        return when {
            digestAuth != null -> {
                logger.fine("Adding Digest authorization request for ${request.url}")
                digestRequest(request, digestAuth)
            }

            basicAuth != null -> {
                logger.fine("Adding Basic authorization header for ${request.url}")

                /* In RFC 2617 (obsolete), there was no encoding for credentials defined, although
                 one can interpret it as "use ISO-8859-1 encoding". This has been clarified by RFC 7617,
                 which creates a new charset parameter for WWW-Authenticate, which always must be UTF-8.
                 So, UTF-8 encoding for credentials is compatible with all RFC 7617 servers and many,
                 but not all pre-RFC 7617 servers. */
                request.newBuilder()
                        .header(HEADER_AUTHORIZATION, Credentials.basic(username, password.concatToString(), Charsets.UTF_8))
                        .build()
            }

            else -> {
                if (response != null)
                    logger.warning("No supported authentication scheme")
                null
            }
        }
    }

    private fun isAllowedHost(host: String): Boolean {
        if (domain == null || domain.equals(UrlUtils.hostToDomain(host), true))
            return true
        logger.warning("Not authenticating against $host because it doesn't belong to $domain")
        return false
    }

    /**
     * Takes the Basic and Digest challenges of a 401 response.
     *
     * @return false if the cached credentials have already been tried and were rejected
     */
    private fun updateChallenges(response: Response): Boolean {
        var newBasicAuth: Challenge? = null
        var newDigestAuth: Challenge? = null
        for (challenge in response.challenges())
            when {
                "Basic".equals(challenge.scheme, true) -> {
                    if (basicAuth != null) {
                        logger.warning("Basic credentials didn't work last time -> aborting")
                        basicAuth = null
                        return false
                    }
                    newBasicAuth = challenge
                }
                "Digest".equals(challenge.scheme, true) -> {
                    if (digestAuth != null && !"true".equals(challenge.authParams["stale"], true)) {
                        logger.warning("Digest credentials didn't work last time and server nonce has not expired -> aborting")
                        digestAuth = null
                        return false
                    }
                    newDigestAuth = challenge
                }
            }

        basicAuth = newBasicAuth
        digestAuth = newDigestAuth
        return true
    }

    fun digestRequest(request: Request, digest: Challenge?): Request? {
        if (digest == null)
            return null

        val realm = digest.authParams["realm"]
        val opaque = digest.authParams["opaque"]
        val nonce = digest.authParams["nonce"]

        val algorithm = Algorithm.determine(digest.authParams["algorithm"])
        val qop = Protection.selectFrom(digest.authParams["qop"])

        if (realm == null) {
            logger.warning("No realm provided, aborting Digest auth")
            return null
        }
        if (nonce == null) {
            logger.warning("No nonce provided, aborting Digest auth")
            return null
        }

        // build response parameters
        val params = LinkedList<String>()
        params.add("username=${quotedString(username)}")
        params.add("realm=${quotedString(realm)}")
        params.add("nonce=${quotedString(nonce)}")
        if (opaque != null)
            params.add("opaque=${quotedString(opaque)}")

        if (algorithm != null)
            params.add("algorithm=${quotedString(algorithm.algorithm)}")

        val method = request.method
        val digestURI = request.url.encodedPath
        params.add("uri=${quotedString(digestURI)}")

        val response = if (qop != null) {
            params.add("qop=${qop.qop}")
            params.add("cnonce=${quotedString(clientNonce)}")

            val nc = nonceCount.getAndIncrement()
            val ncValue = String.format(Locale.ROOT, "%08x", nc)
            params.add("nc=$ncValue")

            val a1 = digestA1(algorithm, realm, nonce)
            val a2 = digestA2(qop, request)
            if (a1 != null && a2 != null)
                kd(h(a1), "$nonce:$ncValue:$clientNonce:${qop.qop}:${h(a2)}")
            else
                null
        } else {
            logger.finer("Using legacy Digest auth")

            // legacy (backwards compatibility with RFC 2069)
            if (algorithm == Algorithm.MD5) {
                val a1 = "$username:$realm:${password.concatToString()}"
                val a2 = "$method:$digestURI"
                kd(h(a1), nonce + ":" + h(a2))
            } else
                null
        }

        if (response == null)
            return null
        params.add("response=" + quotedString(response))
        return request.newBuilder()
                .header(HEADER_AUTHORIZATION, "Digest " + params.joinToString(", "))
                .build()
    }

    // Contains the password; never log it.
    private fun digestA1(algorithm: Algorithm?, realm: String, nonce: String): String? =
        when (algorithm) {
            Algorithm.MD5 ->
                "$username:$realm:${password.concatToString()}"
            Algorithm.MD5_SESSION ->
                h("$username:$realm:${password.concatToString()}") + ":$nonce:$clientNonce"
            else ->
                null
        }

    private fun digestA2(qop: Protection, request: Request): String? {
        val method = request.method
        val digestURI = request.url.encodedPath
        return when (qop) {
            Protection.Auth ->
                "$method:$digestURI"
            Protection.AuthInt -> {
                try {
                    val body = request.body
                    "$method:$digestURI:" + (if (body != null) h(body) else h(""))
                } catch(e: IOException) {
                    logger.warning("Couldn't get entity-body for hash calculation")
                    null
                }
            }
        }
    }


    private enum class Algorithm(
        val algorithm: String
    ) {
        MD5("MD5"),
        MD5_SESSION("MD5-sess");

        companion object {
            fun determine(paramValue: String?): Algorithm? {
                return when {
                    paramValue == null || MD5.algorithm.equals(paramValue, true) ->
                        MD5
                    MD5_SESSION.algorithm.equals(paramValue, true) ->
                        MD5_SESSION
                    else -> {
                        val logger = Logger.getLogger(Algorithm::javaClass.name)
                        logger.warning("Ignoring unknown hash algorithm: $paramValue")
                        null
                    }
                }
            }
        }
    }

    private enum class Protection(
        val qop: String
    ) {    // quality of protection:
        Auth("auth"),              // authentication only
        AuthInt("auth-int");       // authentication with integrity protection

        companion object {
            fun selectFrom(paramValue: String?): Protection? {
                paramValue?.let {
                    var qopAuth = false
                    var qopAuthInt = false
                    for (qop in paramValue.split(","))
                        when (qop) {
                            "auth" -> qopAuth = true
                            "auth-int" -> qopAuthInt = true
                        }

                    // prefer auth-int as it provides more protection
                    if (qopAuthInt)
                        return AuthInt
                    else if (qopAuth)
                        return Auth
                }
                return null
            }
        }
    }


    override fun authenticate(route: Route?, response: Response) =
            authenticateRequest(response.request, response)

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        if (request.header(HEADER_AUTHORIZATION) == null) {
            // try to apply cached authentication
            val authRequest = authenticateRequest(request, null)
            if (authRequest != null)
                request = authRequest
        }
        return chain.proceed(request)
    }

}