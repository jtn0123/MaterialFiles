/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.webdav.client

import at.bitfire.dav4jvm.DavResource
import java.io.IOException
import java.nio.ByteBuffer
import me.zhanghai.android.files.provider.webdav.FakeWebDavServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [FileByteChannel] with each kind of partial update a server may support, which decides how a
 * write reaches the server: in place, or only from start to end in one upload.
 */
class FileByteChannelTest {
    private lateinit var server: FakeWebDavServer

    private val httpClient = OkHttpClient.Builder().followRedirects(false).build()

    private val client = Client(
        object : Authenticator {
            override fun getAuthentication(authority: Authority): Authentication =
                NoneAuthentication
        }
    )

    @Before
    fun setUp() {
        server = FakeWebDavServer()
        server.start()
        server.addFile("/file.txt", "0123456789")
    }

    @After
    fun tearDown() {
        server.stop()
        assertEquals(emptyList<Throwable>(), server.failures)
    }

    private fun channel(patchSupport: PatchSupport, isAppend: Boolean = false): FileByteChannel =
        FileByteChannel(
            client,
            DavResource(httpClient, "http://127.0.0.1:${server.port}/file.txt".toHttpUrl()),
            patchSupport,
            isAppend,
            "/file.txt"
        )

    private fun FileByteChannel.write(position: Long, content: String): Int {
        position(position)
        return write(ByteBuffer.wrap(content.toByteArray()))
    }

    @Test
    fun sabreDavPatchesEachWriteInPlace() {
        channel(PatchSupport.SABRE).use { channel ->
            assertEquals(2, channel.write(3, "ab"))
            assertEquals(5L, channel.position())
            // Out of order is fine when every write says where it goes.
            assertEquals(1, channel.write(0, "z"))
        }

        assertEquals("z12ab56789", server.fileContent("/file.txt"))
        assertEquals(listOf("PATCH /file.txt", "PATCH /file.txt"), writes())
    }

    @Test
    fun apachePutsEachWriteAsARange() {
        channel(PatchSupport.APACHE).use { channel ->
            assertEquals(3, channel.write(8, "xyz"))
            assertEquals(11L, channel.position())
        }

        // Writing past the end makes the file longer.
        assertEquals("01234567xyz", server.fileContent("/file.txt"))
        assertEquals(listOf("PUT /file.txt bytes=8-10/*"), writes())
    }

    @Test
    fun anAppendingChannelWritesAtTheEndTheServerReports() {
        channel(PatchSupport.SABRE, isAppend = true).use { channel ->
            assertEquals(2, channel.write(ByteBuffer.wrap("ab".toByteArray())))
            // The position is the new end of the file, as the server has it.
            assertEquals(12L, channel.position())
            assertEquals(12L, channel.size())
        }

        assertEquals("0123456789ab", server.fileContent("/file.txt"))
        assertEquals(
            "bytes=10-11",
            server.requestHeaders.getValue("PATCH /file.txt")["x-update-range"]
        )
    }

    @Test
    fun withoutPartialUpdatesTheWritesBecomeOneUploadOnClose() {
        val channel = channel(PatchSupport.NONE)
        assertEquals(3, channel.write(0, "abc"))
        assertEquals(3, channel.write(3, "def"))
        // A write that does not continue the upload cannot be sent at all.
        assertThrows(IOException::class.java) { channel.write(10, "x") }
        channel.close()

        assertEquals("abcdef", server.fileContent("/file.txt"))
        assertEquals(listOf("PUT /file.txt"), writes())
    }

    @Test
    fun theSizeComesFromTheServer() {
        channel(PatchSupport.NONE).use { channel ->
            assertEquals(10L, channel.size())
        }
        assertTrue(server.requests.contains("PROPFIND /file.txt"))
        assertEquals("0", server.requestHeaders.getValue("PROPFIND /file.txt")["depth"])
    }

    @Test
    fun truncatingToNothingUploadsAnEmptyFileAndNothingElseIsSupported() {
        channel(PatchSupport.SABRE).use { channel ->
            assertThrows(IOException::class.java) { channel.truncate(5) }
            assertEquals("0123456789", server.fileContent("/file.txt"))
            channel.truncate(0)
        }

        assertEquals("", server.fileContent("/file.txt"))
    }

    @Test
    fun readingPastTheEndIsTheEndOfTheFile() {
        channel(PatchSupport.NONE).use { channel ->
            channel.position(20)
            assertEquals(-1, channel.read(ByteBuffer.allocate(4)))
        }
        assertTrue(server.requests.toString(), "GET /file.txt bytes=20-" in server.requests.last())
    }

    @Test
    fun aReadReturnsWhatTheServerHasEvenWhenItIsLessThanAsked() {
        val buffer = ByteBuffer.allocate(8)
        channel(PatchSupport.NONE).use { channel ->
            channel.position(6)
            assertEquals(4, channel.read(buffer))
            assertEquals(-1, channel.read(ByteBuffer.allocate(1)))
        }
        assertEquals("6789", String(buffer.array(), 0, 4))
    }

    private fun writes(): List<String> =
        server.requests.filter { it.startsWith("PUT ") || it.startsWith("PATCH ") }
}
