/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * SPDX-License-Identifier: MPL-2.0
 */

package at.bitfire.dav4jvm.exception

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.time.Instant

/**
 * Tests the exceptions which are constructed from a status code and a message (without an HTTP
 * response), as used by callers which only know the status of a request.
 */
class HttpExceptionsTest {

    @Test
    fun testCodesAndMessages() {
        val exceptions = listOf(
            UnauthorizedException("no login") to HttpURLConnection.HTTP_UNAUTHORIZED,
            ForbiddenException("not allowed") to HttpURLConnection.HTTP_FORBIDDEN,
            NotFoundException("gone away") to HttpURLConnection.HTTP_NOT_FOUND,
            ConflictException("conflict") to HttpURLConnection.HTTP_CONFLICT,
            GoneException("gone") to HttpURLConnection.HTTP_GONE,
            PreconditionFailedException("etag") to HttpURLConnection.HTTP_PRECON_FAILED,
            ServiceUnavailableException("later") to HttpURLConnection.HTTP_UNAVAILABLE
        )
        for ((exception, code) in exceptions) {
            assertEquals(code, exception.code)
            assertTrue(exception.message!!.startsWith("HTTP $code "))
            // no HTTP response was given
            assertNull(exception.response)
            assertNull(exception.requestBody)
        }
    }

    @Test
    fun testGenericHttpException() {
        val e = HttpException(HttpURLConnection.HTTP_INTERNAL_ERROR, "Server Error")
        assertEquals(HttpURLConnection.HTTP_INTERNAL_ERROR, e.code)
        assertEquals("HTTP 500 Server Error", e.message)
    }

    @Test
    fun testInvalidProperty() {
        assertEquals("no text", InvalidPropertyException("no text").message)
    }

    @Test
    fun testServiceUnavailable_WithoutResponse() {
        val e = ServiceUnavailableException("later")
        assertNull(e.retryAfter)
        // no server suggestion: use the default delay
        val start = Instant.ofEpochSecond(1_000_000)
        assertEquals(start.plusSeconds(ServiceUnavailableException.DELAY_UNTIL_DEFAULT), e.getDelayUntil(start))
    }

    @Test
    fun testServiceUnavailable_RetryAfterIsClamped() {
        // a suggestion of one second is raised to the minimum delay
        val soon = ServiceUnavailableException(response503("1"))
        val start = Instant.now()
        assertEquals(
            ServiceUnavailableException.DELAY_UNTIL_MIN.toDouble(),
            (soon.getDelayUntil(start).epochSecond - start.epochSecond).toDouble(),
            2.0
        )

        // a suggestion of a year is lowered to the maximum delay
        val late = ServiceUnavailableException(response503((365 * 86400).toString()))
        val start2 = Instant.now()
        assertEquals(
            ServiceUnavailableException.DELAY_UNTIL_MAX.toDouble(),
            (late.getDelayUntil(start2).epochSecond - start2.epochSecond).toDouble(),
            2.0
        )
    }

    @Test
    fun testServiceUnavailable_InvalidRetryAfter() {
        // neither a HTTP-date nor delta-seconds
        val e = ServiceUnavailableException(response503("tomorrow"))
        assertNull(e.retryAfter)
    }

    private fun response503(retryAfter: String) =
        Response.Builder()
            .request(Request.Builder().url("http://www.example.com").get().build())
            .protocol(Protocol.HTTP_1_1)
            .code(503).message("Try later")
            .header("Retry-After", retryAfter)
            .build()

    @Test
    fun testFromResponse() {
        val response = Response.Builder()
            .request(Request.Builder().url("http://www.example.com").get().build())
            .protocol(Protocol.HTTP_1_1)
            .code(HttpURLConnection.HTTP_GONE).message("Gone")
            .build()
        val e = GoneException(response)
        assertEquals(HttpURLConnection.HTTP_GONE, e.code)
        assertEquals("HTTP 410 Gone", e.message)

        // without an explicit start, the delay is calculated from now
        val delay = ServiceUnavailableException("later").getDelayUntil()
        assertEquals(
            ServiceUnavailableException.DELAY_UNTIL_DEFAULT.toDouble(),
            (delay.epochSecond - Instant.now().epochSecond).toDouble(),
            2.0
        )
    }

}
