/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.webdav

import java.io.IOException
import java.nio.ByteBuffer
import java8.nio.channels.SeekableByteChannel
import java8.nio.file.AccessDeniedException
import java8.nio.file.FileSystemException
import java8.nio.file.NoSuchFileException
import java8.nio.file.OpenOption
import java8.nio.file.StandardOpenOption
import me.zhanghai.android.files.provider.webdav.FakeWebDavServer.CannedResponse
import me.zhanghai.android.files.provider.webdav.client.Authentication
import me.zhanghai.android.files.provider.webdav.client.Authenticator
import me.zhanghai.android.files.provider.webdav.client.Authority
import me.zhanghai.android.files.provider.webdav.client.Client
import me.zhanghai.android.files.provider.webdav.client.NoneAuthentication
import me.zhanghai.android.files.provider.webdav.client.Protocol
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What a stream or a channel of [WebDavFileSystemProvider] reports when the server refuses it:
 * the file system exception for the file, as for any other provider, and never the WebDAV
 * library's own exceptions, which are not even [IOException]s.
 */
class WebDavStreamFailuresTest {
    private lateinit var server: FakeWebDavServer

    private lateinit var authority: Authority

    @Before
    fun setUp() {
        server = FakeWebDavServer()
        server.start()
        authority = Authority(Protocol.DAV, "127.0.0.1", server.port, "test")
        WebDavFileSystemProvider.client = Client(
            object : Authenticator {
                override fun getAuthentication(authority: Authority): Authentication =
                    NoneAuthentication
            }
        )
    }

    @After
    fun tearDown() {
        WebDavFileSystemProvider.getOrNewFileSystem(authority).close()
        server.stop()
        assertEquals(emptyList<Throwable>(), server.failures)
    }

    private fun path(path: String): WebDavPath =
        WebDavFileSystemProvider.getOrNewFileSystem(authority).getPath(path)

    private fun openChannel(path: String, vararg options: OpenOption): SeekableByteChannel =
        WebDavFileSystemProvider.newByteChannel(path(path), setOf(*options))

    private fun openWritingChannel(path: String): SeekableByteChannel =
        openChannel(path, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)

    @Test
    fun aRefusedUploadIsReportedForItsFileWhenTheStreamIsClosed() {
        server.refusedRequests += "PUT /new.txt"

        val denied = assertThrows(AccessDeniedException::class.java) {
            WebDavFileSystemProvider.newOutputStream(path("/new.txt"))
                .use { it.write("data".toByteArray()) }
        }

        assertEquals("/new.txt", denied.file)
        assertFalse(server.exists("/new.txt"))
    }

    @Test
    fun aServerErrorOnUploadIsReportedForItsFile() {
        server.cannedResponses["PUT /new.txt"] = CannedResponse(507, "Insufficient Storage")

        val failure = assertThrows(FileSystemException::class.java) {
            WebDavFileSystemProvider.newOutputStream(path("/new.txt"))
                .use { it.write("data".toByteArray()) }
        }

        assertEquals("/new.txt", failure.file)
        assertTrue(failure.reason, failure.reason.contains("507"))
    }

    @Test
    fun aRefusedPartialUpdateIsReportedForItsFileByTheWrite() {
        server.addFile("/file.txt", "0123456789")
        server.davHeader = "1, 2, sabredav-partialupdate"
        server.refusedRequests += "PATCH /file.txt"

        openWritingChannel("/file.txt").use { channel ->
            val denied = assertThrows(AccessDeniedException::class.java) {
                channel.write(ByteBuffer.wrap("ab".toByteArray()))
            }
            assertEquals("/file.txt", denied.file)
            // Nothing was written, so the channel did not move either.
            assertEquals(0L, channel.position())
        }
        assertEquals("0123456789", server.fileContent("/file.txt"))
    }

    @Test
    fun aRefusedSequentialUploadIsReportedForItsFileWhenTheChannelIsClosed() {
        server.addFile("/file.txt", "old")
        server.refusedRequests += "PUT /file.txt"

        val channel = openWritingChannel("/file.txt")
        assertEquals(3, channel.write(ByteBuffer.wrap("new".toByteArray())))
        val denied = assertThrows(AccessDeniedException::class.java) { channel.close() }

        assertEquals("/file.txt", denied.file)
        assertEquals("old", server.fileContent("/file.txt"))
    }

    @Test
    fun theSizeOfAFileThatHasGoneIsNoSuchFile() {
        server.addFile("/file.txt", "content")

        openChannel("/file.txt", StandardOpenOption.READ).use { channel ->
            assertEquals(7L, channel.size())
            server.cannedResponses["PROPFIND /file.txt"] = CannedResponse(404)
            val missing = assertThrows(NoSuchFileException::class.java) { channel.size() }
            assertEquals("/file.txt", missing.file)
        }
    }

    @Test
    fun aRefusedTruncationIsReportedForItsFile() {
        server.addFile("/file.txt", "content")
        server.refusedRequests += "PUT /file.txt"

        openWritingChannel("/file.txt").use { channel ->
            val denied = assertThrows(AccessDeniedException::class.java) { channel.truncate(0) }
            assertEquals("/file.txt", denied.file)
        }
        assertEquals("content", server.fileContent("/file.txt"))
    }

    @Test
    fun aRefusedReadIsReportedForItsFile() {
        server.addFile("/file.txt", "content")
        server.refusedRequests += "GET /file.txt"

        openChannel("/file.txt", StandardOpenOption.READ).use { channel ->
            val denied = assertThrows(AccessDeniedException::class.java) {
                channel.read(ByteBuffer.allocate(4))
            }
            assertEquals("/file.txt", denied.file)
        }
    }

    @Test
    fun aServerThatIgnoresTheRangeFailsTheReadRatherThanReturnTheWrongBytes() {
        server.addFile("/file.txt", "0123456789")
        server.cannedResponses["GET /file.txt"] = CannedResponse(200, "0123456789")

        openChannel("/file.txt", StandardOpenOption.READ).use { channel ->
            channel.position(5)
            val failure = assertThrows(IOException::class.java) {
                channel.read(ByteBuffer.allocate(4))
            }
            assertTrue(failure.toString(), failure.toString().contains("200"))
        }
    }
}
