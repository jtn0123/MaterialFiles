/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.webdav

import at.bitfire.dav4jvm.exception.PreconditionFailedException
import java.nio.ByteBuffer
import java.time.Instant
import java8.nio.file.AccessDeniedException
import java8.nio.file.DirectoryStream
import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.NoSuchFileException
import java8.nio.file.Path
import java8.nio.file.StandardCopyOption
import java8.nio.file.StandardOpenOption
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.provider.common.ProgressCopyOption
import me.zhanghai.android.files.provider.webdav.client.Authentication
import me.zhanghai.android.files.provider.webdav.client.Authenticator
import me.zhanghai.android.files.provider.webdav.client.Authority
import me.zhanghai.android.files.provider.webdav.client.Client
import me.zhanghai.android.files.provider.webdav.client.NoneAuthentication
import me.zhanghai.android.files.provider.webdav.client.PasswordAuthentication
import me.zhanghai.android.files.provider.webdav.client.Protocol
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The WebDAV provider against [FakeWebDavServer], which is where the shapes the provider is
 * careful about (a collection addressed with a trailing slash, a replacement written beside its
 * target) can actually be observed.
 */
class WebDavProviderTest {
    private lateinit var server: FakeWebDavServer

    private lateinit var authority: Authority

    @Before
    fun setUp() {
        server = startServer()
    }

    @After
    fun tearDown() {
        server.stop()
        assertEquals(emptyList<Throwable>(), server.failures)
    }

    private fun startServer(
        password: String? = null,
        authentication: Authentication = NoneAuthentication
    ): FakeWebDavServer {
        val server = FakeWebDavServer(password)
        server.start()
        authority = Authority(Protocol.DAV, "127.0.0.1", server.port, "test")
        WebDavFileSystemProvider.client = Client(
            object : Authenticator {
                override fun getAuthentication(authority: Authority): Authentication =
                    authentication
            }
        )
        return server
    }

    private fun path(path: String): WebDavPath =
        WebDavFileSystemProvider.getOrNewFileSystem(authority).getPath(path)

    private fun readAttributes(path: String): BasicFileAttributes =
        WebDavFileSystemProvider.readAttributes(path(path), BasicFileAttributes::class.java)

    @Test
    fun readsTheAttributesOfAFileAndOfACollection() {
        val modified = Instant.ofEpochSecond(1_600_000_000)
        server.addFile("/file.txt", "hello", modified)
        server.addCollection("/dir")

        val fileAttributes = readAttributes("/file.txt")
        assertTrue(fileAttributes.isRegularFile)
        assertEquals(5L, fileAttributes.size())
        assertEquals(modified.toEpochMilli(), fileAttributes.lastModifiedTime().toMillis())
        // The fake serves a creation date of its own, so it is not the modified time.
        assertEquals(
            Instant.ofEpochSecond(1_500_000_000).toEpochMilli(),
            fileAttributes.creationTime().toMillis()
        )
        assertEquals(path("/file.txt"), fileAttributes.fileKey())

        val directoryAttributes = readAttributes("/dir")
        assertTrue(directoryAttributes.isDirectory)
        assertEquals(0L, directoryAttributes.size())
        // Without a creation date the modified time stands in for it.
        assertEquals(
            directoryAttributes.lastModifiedTime(),
            directoryAttributes.creationTime()
        )
    }

    @Test
    fun fallsBackToTheModifiedTimeWhenTheCreationDateIsNotAnHttpDate() {
        // Servers commonly send an ISO 8601 creationdate, which is not an HTTP date.
        server.addFile("/file.txt", "hello", creationDate = "2020-01-01T00:00:00Z")

        val attributes = readAttributes("/file.txt")
        assertEquals(attributes.lastModifiedTime(), attributes.creationTime())
    }

    @Test
    fun listsTheMembersOfACollectionAndReusesTheirAttributes() {
        server.addCollection("/dir")
        server.addFile("/dir/a.txt", "a")
        server.addCollection("/dir/sub")
        server.addFile("/dir/sub/deep.txt", "deep")

        val members = WebDavFileSystemProvider
            .newDirectoryStream(path("/dir"), DirectoryStream.Filter<Path> { true })
            .use { it.toList() }
        assertEquals(
            listOf("/dir/a.txt", "/dir/sub"),
            members.map { it.toString() }.sorted()
        )

        // The listing already carried every member's properties, so reading them asks nothing.
        val propfindCount = server.requests.count { it.startsWith("PROPFIND") }
        val memberAttributes = members.associate {
            it.toString() to WebDavFileSystemProvider
                .readAttributes(it, BasicFileAttributes::class.java)
        }
        assertEquals(propfindCount, server.requests.count { it.startsWith("PROPFIND") })
        assertTrue(memberAttributes.getValue("/dir/a.txt").isRegularFile)
        assertEquals(1L, memberAttributes.getValue("/dir/a.txt").size())
        assertTrue(memberAttributes.getValue("/dir/sub").isDirectory)

        // The cached response is used once only; a second read goes back to the server.
        WebDavFileSystemProvider.readAttributes(members[0], BasicFileAttributes::class.java)
        assertEquals(propfindCount + 1, server.requests.count { it.startsWith("PROPFIND") })
    }

    @Test
    fun deletesAFileDirectlyAndACollectionWithATrailingSlash() {
        server.addFile("/file.txt", "hello")
        server.addCollection("/dir")

        server.addFile("/other.txt", "hello")

        WebDavFileSystemProvider.delete(path("/file.txt"))
        WebDavFileSystemProvider.delete(path("/dir"))
        // The client addresses a file, which is what it assumes by default, as it is named.
        WebDavFileSystemProvider.client.delete(path("/other.txt"))

        assertFalse(server.exists("/file.txt"))
        assertFalse(server.exists("/dir"))
        assertFalse(server.exists("/other.txt"))
        assertTrue("DELETE /file.txt" in server.requests)
        assertTrue("DELETE /dir/" in server.requests)
        assertTrue("DELETE /other.txt" in server.requests)
    }

    @Test
    fun mapsAMissingFileAndAForbiddenOneToTheirNioExceptions() {
        server.addFile("/forbidden.txt", "hello")
        server.refusedRequests += "PROPFIND /forbidden.txt"

        val notFound = assertThrows(NoSuchFileException::class.java) {
            readAttributes("/missing.txt")
        }
        assertEquals("/missing.txt", notFound.file)
        val denied = assertThrows(AccessDeniedException::class.java) {
            readAttributes("/forbidden.txt")
        }
        assertEquals("/forbidden.txt", denied.file)
    }

    @Test
    fun writesWithPutAndReadsBackWithGet() {
        WebDavFileSystemProvider.newOutputStream(path("/new.txt"))
            .use { it.write("written".toByteArray()) }

        assertEquals("written", server.fileContent("/new.txt"))
        assertTrue("PUT /new.txt" in server.requests)
        val content = WebDavFileSystemProvider.newInputStream(path("/new.txt"))
            .use { String(it.readBytes()) }
        assertEquals("written", content)
    }

    @Test
    fun copiesOverAnExistingFileThroughASiblingSoAFailureCannotLoseIt() {
        server.addFile("/source.txt", "source")
        server.addFile("/target.txt", "old")

        WebDavFileSystemProvider.copy(
            path("/source.txt"),
            path("/target.txt"),
            StandardCopyOption.REPLACE_EXISTING
        )

        assertEquals("source", server.fileContent("/target.txt"))
        assertEquals("source", server.fileContent("/source.txt"))
        val replacement = server.requests.single { it.startsWith("PUT /.target.txt.") }
            .removePrefix("PUT ")
        assertTrue(replacement.endsWith(".part"))
        assertTrue("MOVE $replacement -> /target.txt" in server.requests)
        // Nothing of the replacement is left behind.
        assertEquals(setOf("", "/source.txt", "/target.txt"), server.paths())
    }

    @Test
    fun refusesToCopyOntoAnExistingFileUnlessAskedToReplaceIt() {
        server.addFile("/source.txt", "source")
        server.addFile("/target.txt", "old")

        assertThrows(FileAlreadyExistsException::class.java) {
            WebDavFileSystemProvider.copy(path("/source.txt"), path("/target.txt"))
        }
        assertEquals("old", server.fileContent("/target.txt"))
    }

    @Test
    fun copyingACollectionCreatesItWithoutItsMembers() {
        server.addCollection("/dir")
        server.addFile("/dir/a.txt", "a")

        WebDavFileSystemProvider.copy(path("/dir"), path("/copy"))

        assertTrue(server.exists("/copy"))
        assertFalse(server.exists("/copy/a.txt"))
        // A MKCOL target is a collection by definition, so it gets the trailing slash.
        assertTrue(server.requests.toString(), "MKCOL /copy/" in server.requests)
    }

    @Test
    fun movesACollectionWithATrailingSlashOnBothEnds() {
        server.addCollection("/dir")
        server.addFile("/dir/a.txt", "a")

        WebDavFileSystemProvider.move(path("/dir"), path("/moved"))

        assertTrue(server.exists("/moved"))
        assertEquals("a", server.fileContent("/moved/a.txt"))
        assertFalse(server.exists("/dir"))
        assertTrue("MOVE /dir/ -> /moved/" in server.requests)
    }

    @Test
    fun movingOntoAnExistingFileNeedsReplaceExisting() {
        server.addFile("/source.txt", "source")
        server.addFile("/target.txt", "old")

        assertThrows(FileAlreadyExistsException::class.java) {
            WebDavFileSystemProvider.move(path("/source.txt"), path("/target.txt"))
        }
        assertEquals("old", server.fileContent("/target.txt"))
        // The client does not overwrite unless it is told to, and the server refuses the move.
        assertThrows(PreconditionFailedException::class.java) {
            WebDavFileSystemProvider.client.move(path("/source.txt"), path("/target.txt"))
        }
        assertEquals("old", server.fileContent("/target.txt"))

        var reported = 0L
        WebDavFileSystemProvider.move(
            path("/source.txt"),
            path("/target.txt"),
            StandardCopyOption.REPLACE_EXISTING,
            ProgressCopyOption(0) { reported += it }
        )
        assertEquals("source", server.fileContent("/target.txt"))
        // A move the server did itself still reports the whole file as progress.
        assertEquals(6L, reported)
        assertFalse(server.exists("/source.txt"))
        assertTrue("MOVE /source.txt -> /target.txt" in server.requests)
    }

    @Test
    fun authenticatesWithABasicPasswordAfterTheFirstChallenge() {
        server.stop()
        server = startServer("test:secret", PasswordAuthentication("secret"))
        server.addFile("/file.txt", "hello")

        assertEquals(5L, readAttributes("/file.txt").size())
        // Unauthenticated first, then again with the credentials the challenge asked for.
        assertEquals(listOf("PROPFIND /file.txt", "PROPFIND /file.txt"), server.requests.toList())

        // The credentials are remembered for the next request to the same host.
        assertEquals(5L, readAttributes("/file.txt").size())
        assertEquals(3, server.requests.size)
    }

    @Test
    fun aRefusedWriteIsReportedAndWhateverItWroteIsCleanedUp() {
        server.addFile("/source.txt", "source")
        server.refusedRequests += "PUT /target.txt"
        server.refusedRequests += "DELETE /target.txt"

        val denied = assertThrows(AccessDeniedException::class.java) {
            WebDavFileSystemProvider.copy(path("/source.txt"), path("/target.txt"))
        }
        assertEquals("/target.txt", denied.file)
        // The failed transfer is deleted, and that the server refuses that too is only suppressed.
        assertTrue("DELETE /target.txt" in server.requests)
        assertEquals(1, denied.suppressed.size)
        assertFalse(server.exists("/target.txt"))
    }

    @Test
    fun aRefusedCollectionCreationIsReported() {
        server.addCollection("/dir")
        server.refusedRequests += "MKCOL /copy"

        val denied = assertThrows(AccessDeniedException::class.java) {
            WebDavFileSystemProvider.copy(path("/dir"), path("/copy"))
        }
        assertEquals("/copy", denied.file)
        assertFalse(server.exists("/copy"))
    }

    @Test
    fun readsAByteChannelInRanges() {
        server.addFile("/file.txt", "0123456789")

        val read = WebDavFileSystemProvider
            .newByteChannel(path("/file.txt"), setOf(StandardOpenOption.READ))
            .use { channel ->
                val buffer = ByteBuffer.allocate(4)
                assertEquals(4, channel.read(buffer))
                channel.position(8)
                val rest = ByteBuffer.allocate(4)
                // Only two bytes are left, and reading at the end reports the end of the file.
                assertEquals(2, channel.read(rest))
                assertEquals(-1, channel.read(rest))
                String(buffer.array()) to String(rest.array(), 0, 2)
            }
        assertEquals("0123" to "89", read)
        // The channel asks what the server can do, then reads only the ranges it needs.
        assertTrue(server.requests.any { it.startsWith("OPTIONS ") })
        val gets = server.requests.filter { it.startsWith("GET ") }
        assertTrue(gets.toString(), gets.isNotEmpty() && gets.all { "bytes=" in it })
    }

    @Test
    fun writesAByteChannelInChunksWithoutPartialUpdates() {
        server.addFile("/file.txt", "old")

        val options = setOf(StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
        WebDavFileSystemProvider.newByteChannel(path("/file.txt"), options)
            .use { channel ->
                val first = ByteBuffer.wrap("abc".toByteArray())
                assertEquals(3, channel.write(first))
                // A channel write consumes what it wrote, and the next one continues after it.
                assertFalse(first.hasRemaining())
                assertEquals(3L, channel.position())
                assertEquals(3, channel.write(ByteBuffer.wrap("def".toByteArray())))
            }
        assertEquals("abcdef", server.fileContent("/file.txt"))
    }

    @Test
    fun pathsAreNotJavaFilesAndCannotBeResolvedToRealPaths() {
        assertThrows(UnsupportedOperationException::class.java) { path("/file.txt").toFile() }
        assertThrows(UnsupportedOperationException::class.java) { path("/file.txt").toRealPath() }
    }
}
