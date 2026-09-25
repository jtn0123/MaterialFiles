/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.webdav.client

import at.bitfire.dav4jvm.DavResource
import at.bitfire.dav4jvm.exception.ConflictException
import at.bitfire.dav4jvm.exception.ForbiddenException
import at.bitfire.dav4jvm.exception.HttpException
import at.bitfire.dav4jvm.exception.NotFoundException
import at.bitfire.dav4jvm.exception.PreconditionFailedException
import at.bitfire.dav4jvm.exception.ServiceUnavailableException
import at.bitfire.dav4jvm.exception.UnauthorizedException
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import me.zhanghai.android.files.provider.webdav.FakeWebDavServer
import me.zhanghai.android.files.provider.webdav.FakeWebDavServer.CannedResponse
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The requests this app adds to dav4jvm - a ranged GET, a streaming PUT and the two kinds of
 * partial update - against [FakeWebDavServer], with an HTTP client of the test's own so that what
 * happens to its connections can be seen.
 */
class DavResourceCompatTest {
    private lateinit var server: FakeWebDavServer

    private lateinit var httpClient: OkHttpClient

    @Before
    fun setUp() {
        server = FakeWebDavServer()
        server.start()
        httpClient = OkHttpClient.Builder().followRedirects(false).build()
    }

    @After
    fun tearDown() {
        server.stop()
        assertEquals(emptyList<Throwable>(), server.failures)
    }

    private fun resource(path: String): DavResource =
        DavResource(httpClient, "http://127.0.0.1:${server.port}$path".toHttpUrl())

    @Test
    fun getReturnsTheBodyAndAnEmptyStreamForAResponseWithoutOne() {
        server.addFile("/file.txt", "hello")
        server.cannedResponses["GET /empty"] = CannedResponse(204)

        assertEquals(
            "hello",
            resource("/file.txt").getCompat("*/*", null).use { String(it.readBytes()) }
        )
        val empty = resource("/empty").getCompat("*/*", null).use { it.readBytes() }
        assertEquals(0, empty.size)
        assertEquals("*/*", server.requestHeaders.getValue("GET /file.txt")["accept"])
    }

    @Test
    fun getMapsEachErrorStatusToItsException() {
        val expected = linkedMapOf(
            401 to UnauthorizedException::class.java,
            403 to ForbiddenException::class.java,
            404 to NotFoundException::class.java,
            409 to ConflictException::class.java,
            412 to PreconditionFailedException::class.java,
            500 to HttpException::class.java,
            503 to ServiceUnavailableException::class.java
        )
        for ((code, exceptionClass) in expected) {
            server.cannedResponses["GET /status$code"] = CannedResponse(code, "error $code")
            val exception = assertThrows(HttpException::class.java) {
                resource("/status$code").getCompat("*/*", null)
            }
            assertSame("$code", exceptionClass, exception.javaClass)
            assertEquals(code, exception.code)
        }
        // An error response is consumed by its exception, so every connection went back.
        assertEquals(httpClient.connectionPool.connectionCount(), idleConnectionCount())
    }

    @Test
    fun aRangeIsReadOnlyFromAPartialResponse() {
        server.addFile("/file.txt", "0123456789")
        server.cannedResponses["GET /whole.txt"] = CannedResponse(200, "0123456789")

        val range = resource("/file.txt").getRangeCompat("*/*", 2, 3, null)
            .use { String(it.readBytes()) }
        assertEquals("234", range)
        assertTrue("GET /file.txt bytes=2-4" in server.requests)

        // A server that ignores the range sends the whole file, which is not what was asked for.
        val ignored = assertThrows(HttpException::class.java) {
            resource("/whole.txt").getRangeCompat("*/*", 2, 3, null)
        }
        assertEquals(200, ignored.code)
        // At the end of the file the server says the range cannot be satisfied.
        val pastTheEnd = assertThrows(HttpException::class.java) {
            resource("/file.txt").getRangeCompat("*/*", 10, 3, null)
        }
        assertEquals(416, pastTheEnd.code)
        assertEquals(httpClient.connectionPool.connectionCount(), idleConnectionCount())
    }

    @Test
    fun putStreamsTheWholeBodyAndSendsItsConditions() {
        val content = ByteArray(256 * 1024) { (it % 251).toByte() }

        resource("/file.bin").putCompat(
            ifETag = "etag",
            ifNoneMatch = true,
            headers = mapOf("X-Custom" to "value")
        ).use { outputStream ->
            // Much more than the pipe holds, in pieces of odd sizes.
            var offset = 0
            while (offset < content.size) {
                val length = minOf(10_007, content.size - offset)
                outputStream.write(content, offset, length)
                offset += length
            }
        }

        // Closing waits for the response, so the server has the whole file by now.
        assertTrue(content.contentEquals(server.fileBytes("/file.bin")))
        val headers = server.requestHeaders.getValue("PUT /file.bin")
        assertEquals("\"etag\"", headers["if-match"])
        assertEquals("*", headers["if-none-match"])
        assertEquals("value", headers["x-custom"])
    }

    @Test
    fun aRefusedUploadIsReportedWhenTheStreamIsClosed() {
        server.refusedRequests += "PUT /forbidden.txt"
        server.cannedResponses["PUT /exists.txt"] = CannedResponse(412)
        server.cannedResponses["PUT /full.txt"] = CannedResponse(507, "Insufficient Storage")

        val stream = resource("/forbidden.txt").putCompat()
        stream.write("data".toByteArray())
        assertThrows(ForbiddenException::class.java) { stream.close() }
        assertThrows(PreconditionFailedException::class.java) {
            resource("/exists.txt").putCompat(ifNoneMatch = true).use { it.write(1) }
        }
        val full = assertThrows(HttpException::class.java) {
            resource("/full.txt").putCompat().use { it.write("data".toByteArray()) }
        }
        assertEquals(507, full.code)
        assertFalse(server.exists("/forbidden.txt"))
    }

    @Test
    fun aSuccessfulUploadGivesItsConnectionBack() {
        // Apache answers a PUT with a small HTML page, which has to be read or discarded.
        server.cannedResponses["PUT /created.txt"] = CannedResponse(
            201,
            "<html><body>Created</body></html>",
            mapOf("Content-Type" to "text/html")
        )

        resource("/created.txt").putCompat().use { it.write("data".toByteArray()) }

        assertEquals(1, httpClient.connectionPool.connectionCount())
        assertEquals(1, idleConnectionCount())
    }

    @Test
    fun anUnreachableServerFailsTheUploadInsteadOfBlockingIt() {
        val port = server.port
        server.stop()
        val stream = DavResource(httpClient, "http://127.0.0.1:$port/file.bin".toHttpUrl())
            .putCompat()

        // Far more than the pipe between the writer and the request body holds.
        val failure = runWithTimeout {
            runCatching {
                stream.use { it.write(ByteArray(1024 * 1024)) }
            }.exceptionOrNull()
        }
        // The failure is the connection's, not the pipe's.
        assertTrue(failure.toString(), failure is ConnectException)
    }

    @Test
    fun aServerThatRefusesBeforeReadingTheUploadDoesNotBlockIt() {
        // Like nginx refusing an upload that is too large: answer, and hang up without reading.
        val refusingServer = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val serverThread = Thread {
            refusingServer.accept().use { socket ->
                val headers = socket.getInputStream().bufferedReader()
                while (headers.readLine().orEmpty().isNotEmpty()) {
                    // Only the request line and the headers are read.
                }
                socket.getOutputStream().write(
                    "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .toByteArray()
                )
                socket.getOutputStream().flush()
            }
        }.apply { isDaemon = true }
        serverThread.start()
        try {
            val url = "http://127.0.0.1:${refusingServer.localPort}/file.bin".toHttpUrl()
            val stream = DavResource(httpClient, url).putCompat()

            val failure = runWithTimeout {
                runCatching {
                    stream.use { it.write(ByteArray(16 * 1024 * 1024)) }
                }.exceptionOrNull()
            }
            // Whether the refusal or the broken connection is seen first depends on timing.
            assertTrue(
                failure.toString(),
                failure is ForbiddenException || failure is IOException
            )
        } finally {
            refusingServer.close()
        }
    }

    @Test
    fun thePartialUpdateAServerSupportsIsFoundInItsOptions() {
        server.addFile("/file.txt", "hello")
        assertEquals(PatchSupport.NONE, resource("/file.txt").getPatchSupport())

        server.davHeader = "1, 2, sabredav-partialupdate"
        assertEquals(PatchSupport.SABRE, resource("/file.txt").getPatchSupport())

        // Apache's property set is only trusted when it is Apache that says it.
        server.davHeader = "1, 2, <http://apache.org/dav/propset/fs/1>"
        assertEquals(PatchSupport.NONE, resource("/file.txt").getPatchSupport())
        server.serverHeader = "Apache/2.4.62 (Unix)"
        assertEquals(PatchSupport.APACHE, resource("/file.txt").getPatchSupport())
    }

    @Test
    fun aPatchUpdatesOnlyItsRange() {
        server.addFile("/file.txt", "0123456789")
        val buffer = ByteBuffer.wrap("xxabcxx".toByteArray(), 2, 3)

        resource("/file.txt").patchCompat(buffer, 4) {}

        assertEquals("0123abc789", server.fileContent("/file.txt"))
        val headers = server.requestHeaders.getValue("PATCH /file.txt")
        assertEquals("bytes=4-6", headers["x-update-range"])
        assertEquals("application/x-sabredav-partialupdate", headers["content-type"])
        // Only what was left in the buffer was sent, and the buffer is consumed, which is how a
        // channel knows how much it wrote.
        assertEquals(5, buffer.position())
        assertFalse(buffer.hasRemaining())
    }

    @Test
    fun aRangedPutUpdatesOnlyItsRangeAndCanExtendTheFile() {
        server.addFile("/file.txt", "0123456789")

        resource("/file.txt").putRangeCompat(ByteBuffer.wrap("ab".toByteArray()), 9) {}

        assertEquals("012345678ab", server.fileContent("/file.txt"))
        assertTrue(server.requests.toString(), "PUT /file.txt bytes=9-10/*" in server.requests)
    }

    @Test
    fun aRefusedPartialUpdateIsReported() {
        server.addFile("/file.txt", "0123456789")
        server.refusedRequests += "PATCH /file.txt"
        server.cannedResponses["PUT /file.txt"] = CannedResponse(409)

        assertThrows(ForbiddenException::class.java) {
            resource("/file.txt").patchCompat(ByteBuffer.wrap("ab".toByteArray()), 0) {}
        }
        assertThrows(ConflictException::class.java) {
            resource("/file.txt").putRangeCompat(ByteBuffer.wrap("ab".toByteArray()), 0) {}
        }
        assertEquals("0123456789", server.fileContent("/file.txt"))
    }

    @Test
    fun aPartialUpdateSendsItsBodyAgainAfterAnAuthenticationChallenge() {
        server.stop()
        server = FakeWebDavServer("user:secret")
        server.start()
        server.addFile("/file.txt", "0123456789")
        httpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .authenticator { _, response ->
                response.request.newBuilder()
                    .header("Authorization", Credentials.basic("user", "secret"))
                    .build()
            }
            .build()

        resource("/file.txt").patchCompat(ByteBuffer.wrap("abc".toByteArray()), 2) {}

        // The first attempt was refused, and the second one still had the whole body.
        assertEquals(2, server.requests.count { it == "PATCH /file.txt" })
        assertEquals("01abc56789", server.fileContent("/file.txt"))
    }

    private fun <T> runWithTimeout(block: () -> T): T {
        val executor = Executors.newSingleThreadExecutor { Thread(it).apply { isDaemon = true } }
        try {
            return executor.submit(Callable(block)).get(10, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun idleConnectionCount(): Int {
        // The pool learns of a released connection on the thread that released it, but give it
        // a moment in case that was one of OkHttp's own.
        repeat(50) {
            if (httpClient.connectionPool.idleConnectionCount() ==
                httpClient.connectionPool.connectionCount()
            ) {
                return httpClient.connectionPool.idleConnectionCount()
            }
            Thread.sleep(20)
        }
        return httpClient.connectionPool.idleConnectionCount()
    }
}
