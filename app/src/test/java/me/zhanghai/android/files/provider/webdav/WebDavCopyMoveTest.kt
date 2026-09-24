/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.webdav

import java8.nio.file.AccessDeniedException
import java8.nio.file.CopyOption
import java8.nio.file.FileSystemException
import java8.nio.file.NoSuchFileException
import java8.nio.file.StandardCopyOption
import me.zhanghai.android.files.provider.common.ProgressCopyOption
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [WebDavCopyMove] against [FakeWebDavServer]: a move between directories, what the server does
 * itself and what is left to the client, and what a failure on either side leaves behind.
 */
class WebDavCopyMoveTest {
    private lateinit var server: FakeWebDavServer

    private lateinit var authority: Authority

    private var progress = 0L

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
        server.addCollection("/a")
        server.addCollection("/b")
    }

    @After
    fun tearDown() {
        WebDavFileSystemProvider.getOrNewFileSystem(authority).close()
        server.stop()
        assertEquals(emptyList<Throwable>(), server.failures)
    }

    private fun path(path: String): WebDavPath =
        WebDavFileSystemProvider.getOrNewFileSystem(authority).getPath(path)

    private fun options(vararg options: CopyOption): Array<CopyOption> =
        arrayOf(*options, ProgressCopyOption(0) { progress += it })

    private fun copy(source: String, target: String, vararg options: CopyOption) {
        WebDavFileSystemProvider.copy(path(source), path(target), *options(*options))
    }

    private fun move(source: String, target: String, vararg options: CopyOption) {
        WebDavFileSystemProvider.move(path(source), path(target), *options(*options))
    }

    private fun transfers(): List<String> =
        server.requests.filter { it.startsWith("GET ") || it.startsWith("PUT ") }

    @Test
    fun aFileMovesIntoAnotherDirectoryWithOneRequestThatReplacesNothing() {
        server.addFile("/a/file.txt", "content")

        move("/a/file.txt", "/b/file.txt")

        assertEquals("content", server.fileContent("/b/file.txt"))
        assertFalse(server.exists("/a/file.txt"))
        assertEquals(emptyList<String>(), transfers())
        val headers = server.requestHeaders.getValue("MOVE /a/file.txt -> /b/file.txt")
        assertEquals("F", headers["overwrite"])
        assertEquals(7L, progress)
    }

    @Test
    fun aFileMovedOverAnotherInAnotherDirectoryAsksTheServerToOverwrite() {
        server.addFile("/a/file.txt", "new")
        server.addFile("/b/file.txt", "old")

        move("/a/file.txt", "/b/file.txt", StandardCopyOption.REPLACE_EXISTING)

        assertEquals("new", server.fileContent("/b/file.txt"))
        assertFalse(server.exists("/a/file.txt"))
        val headers = server.requestHeaders.getValue("MOVE /a/file.txt -> /b/file.txt")
        // Overwrite defaults to T, so it is simply not sent.
        assertNull(headers["overwrite"])
    }

    @Test
    fun aRefusedMoveOfAFileFallsBackToCopyingAndDeleting() {
        server.addFile("/a/file.txt", "content")
        server.refusedRequests += "MOVE /a/file.txt"

        move("/a/file.txt", "/b/file.txt")

        assertEquals("content", server.fileContent("/b/file.txt"))
        assertFalse(server.exists("/a/file.txt"))
        assertEquals(listOf("GET /a/file.txt", "PUT /b/file.txt"), transfers())
        assertTrue("DELETE /a/file.txt" in server.requests)
        // The progress is the copy's, and nothing is counted twice.
        assertEquals(7L, progress)
    }

    @Test
    fun aRefusedAtomicMoveIsReportedForBothPathsAndChangesNothing() {
        server.addFile("/a/file.txt", "content")
        server.refusedRequests += "MOVE /a/file.txt"

        val denied = assertThrows(AccessDeniedException::class.java) {
            move("/a/file.txt", "/b/file.txt", StandardCopyOption.ATOMIC_MOVE)
        }

        assertEquals("/a/file.txt", denied.file)
        assertEquals("/b/file.txt", denied.otherFile)
        assertEquals("content", server.fileContent("/a/file.txt"))
        assertFalse(server.exists("/b/file.txt"))
        assertEquals(emptyList<String>(), transfers())
    }

    @Test
    fun anEmptyCollectionIsMovedByCopyingWhenTheServerRefusesToMoveIt() {
        server.addCollection("/a/dir")
        server.refusedRequests += "MOVE /a/dir"

        move("/a/dir", "/b/dir")

        assertTrue(server.exists("/b/dir"))
        assertFalse(server.exists("/a/dir"))
        assertTrue("DELETE /a/dir/" in server.requests)
    }

    @Test
    fun aCollectionReplacesAFileByDeletingItFirst() {
        server.addCollection("/a/dir")
        server.addFile("/b/dir", "a file")

        copy("/a/dir", "/b/dir", StandardCopyOption.REPLACE_EXISTING)

        // The file is addressed as a file, and the collection that replaces it as a collection.
        val deleted = server.requests.indexOf("DELETE /b/dir")
        val created = server.requests.indexOf("MKCOL /b/dir/")
        assertTrue(server.requests.toString(), deleted in 0 until created)
        assertEquals(setOf("", "/a", "/a/dir", "/b", "/b/dir"), server.paths())
    }

    @Test
    fun aFileIsCopiedIntoAnotherDirectoryWithItsProgress() {
        val content = "x".repeat(100_000)
        server.addFile("/a/file.txt", content)

        copy("/a/file.txt", "/b/file.txt")

        assertEquals(content, server.fileContent("/b/file.txt"))
        assertEquals(content, server.fileContent("/a/file.txt"))
        assertEquals(100_000L, progress)
    }

    @Test
    fun aFileCopiedOntoItselfIsLeftAloneButCounted() {
        server.addFile("/a/file.txt", "content")

        copy("/a/file.txt", "/a/file.txt", StandardCopyOption.REPLACE_EXISTING)

        assertEquals(emptyList<String>(), transfers())
        assertEquals("content", server.fileContent("/a/file.txt"))
        assertEquals(7L, progress)
    }

    @Test
    fun aMissingSourceIsNoSuchFile() {
        val missing = assertThrows(NoSuchFileException::class.java) {
            copy("/a/missing.txt", "/b/missing.txt")
        }
        assertEquals("/a/missing.txt", missing.file)
        assertFalse(server.exists("/b/missing.txt"))
    }

    @Test
    fun aSourceThatCannotBeReadIsReportedForItAndNothingIsWritten() {
        server.addFile("/a/file.txt", "content")
        server.cannedResponses["GET /a/file.txt"] = CannedResponse(500, "Internal Server Error")

        val failure = assertThrows(FileSystemException::class.java) {
            copy("/a/file.txt", "/b/file.txt")
        }

        assertEquals("/a/file.txt", failure.file)
        assertTrue(failure.reason, failure.reason.contains("500"))
        assertFalse(server.requests.any { it.startsWith("PUT ") })
        assertFalse(server.exists("/b/file.txt"))
    }

    @Test
    fun aServerErrorOnUploadIsReportedForTheTargetAndCleanedUp() {
        server.addFile("/a/file.txt", "content")
        server.cannedResponses["PUT /b/file.txt"] = CannedResponse(507, "Insufficient Storage")

        val failure = assertThrows(FileSystemException::class.java) {
            copy("/a/file.txt", "/b/file.txt")
        }

        assertEquals(FileSystemException::class.java, failure.javaClass)
        assertEquals("/b/file.txt", failure.file)
        assertTrue(failure.reason, failure.reason.contains("507"))
        assertTrue("DELETE /b/file.txt" in server.requests)
        assertFalse(server.exists("/b/file.txt"))
    }

    @Test
    fun aFailedReplacementLeavesTheOriginalInPlace() {
        server.addFile("/a/file.txt", "new")
        server.addFile("/b/file.txt", "old")
        server.cannedResponses["GET /a/file.txt"] = CannedResponse(503)

        assertThrows(FileSystemException::class.java) {
            copy("/a/file.txt", "/b/file.txt", StandardCopyOption.REPLACE_EXISTING)
        }

        assertEquals("old", server.fileContent("/b/file.txt"))
        assertEquals(setOf("", "/a", "/a/file.txt", "/b", "/b/file.txt"), server.paths())
    }
}
