/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.webdav

import java.nio.ByteBuffer
import java8.nio.file.AccessDeniedException
import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.LinkOption
import java8.nio.file.NoSuchFileException
import java8.nio.file.OpenOption
import java8.nio.file.ProviderMismatchException
import java8.nio.file.StandardCopyOption
import java8.nio.file.StandardOpenOption
import java8.nio.file.attribute.BasicFileAttributeView
import java8.nio.file.attribute.FileAttribute
import java8.nio.file.attribute.FileTime
import me.zhanghai.android.files.provider.common.ByteStringPath
import me.zhanghai.android.files.provider.common.TestPath
import me.zhanghai.android.files.provider.common.toByteString
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
 * How [WebDavFileSystemProvider] reads the open options before it asks [FakeWebDavServer] for a
 * stream, and what it does with times it cannot set.
 */
class WebDavOpenOptionsTest {
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
        server.stop()
        assertEquals(emptyList<Throwable>(), server.failures)
    }

    private fun path(path: String): WebDavPath =
        WebDavFileSystemProvider.getOrNewFileSystem(authority).getPath(path)

    @Test
    fun aDownloadStreamCannotBeAskedToWrite() {
        server.addFile("/file.txt", "hello")
        for (option in listOf<OpenOption>(StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            assertThrows(option.toString(), UnsupportedOperationException::class.java) {
                WebDavFileSystemProvider.newInputStream(path("/file.txt"), option)
            }
        }
        assertEquals("hello", server.fileContent("/file.txt"))
    }

    @Test
    fun aDownloadStreamIgnoresTheOptionsForWritingAndNeedsTheFile() {
        server.addFile("/file.txt", "hello")
        val content = WebDavFileSystemProvider.newInputStream(
            path("/file.txt"),
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.CREATE_NEW
        ).use { String(it.readBytes()) }
        assertEquals("hello", content)
        assertThrows(NoSuchFileException::class.java) {
            WebDavFileSystemProvider.newInputStream(
                path("/missing.txt"),
                StandardOpenOption.CREATE
            )
        }
        assertFalse(server.exists("/missing.txt"))
    }

    @Test
    fun aDownloadStreamNotFollowingLinksReadsAFileThatIsNotOne() {
        server.addFile("/file.txt", "hello")
        val content = WebDavFileSystemProvider
            .newInputStream(path("/file.txt"), LinkOption.NOFOLLOW_LINKS)
            .use { String(it.readBytes()) }
        assertEquals("hello", content)
    }

    @Test
    fun aRefusedLookupOrCreationIsReportedForThePath() {
        server.refusedRequests += "PROPFIND /looked-up.txt"
        val lookedUp = assertThrows(AccessDeniedException::class.java) {
            WebDavFileSystemProvider.newInputStream(
                path("/looked-up.txt"),
                LinkOption.NOFOLLOW_LINKS
            )
        }
        assertEquals("/looked-up.txt", lookedUp.file)
        server.refusedRequests += "PUT /created.txt"
        val created = assertThrows(AccessDeniedException::class.java) {
            WebDavFileSystemProvider.newByteChannel(
                path("/created.txt"),
                setOf(
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.CREATE
                )
            )
        }
        assertEquals("/created.txt", created.file)
        assertFalse(server.exists("/created.txt"))
    }

    @Test
    fun aChannelThatMayCreateTheFileCreatesIt() {
        WebDavFileSystemProvider.newByteChannel(
            path("/file.txt"),
            setOf(
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.CREATE
            )
        ).use { it.write(ByteBuffer.wrap("hello".toByteArray())) }
        assertEquals("hello", server.fileContent("/file.txt"))
    }

    @Test
    fun aChannelThatMayNotCreateTheFileNeedsItToExist() {
        assertThrows(NoSuchFileException::class.java) {
            WebDavFileSystemProvider.newByteChannel(
                path("/file.txt"),
                setOf(StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
            )
        }
        assertFalse(server.exists("/file.txt"))
    }

    @Test
    fun aChannelThatMustCreateTheFileFailsOnAnExistingOne() {
        server.addFile("/file.txt", "hello")
        assertThrows(FileAlreadyExistsException::class.java) {
            WebDavFileSystemProvider.newByteChannel(
                path("/file.txt"),
                setOf(
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.CREATE_NEW
                )
            )
        }
        assertEquals("hello", server.fileContent("/file.txt"))
    }

    @Test
    fun aChannelNotFollowingLinksOpensAFileThatIsNotOne() {
        server.addFile("/file.txt", "hello")
        WebDavFileSystemProvider.newByteChannel(
            path("/file.txt"),
            setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        ).use { assertEquals(5L, it.size()) }
    }

    @Test
    fun aChannelCannotBeCreatedWithAttributes() {
        server.addFile("/file.txt", "hello")
        val attribute = object : FileAttribute<String> {
            override fun name(): String = "test:attribute"

            override fun value(): String = "value"
        }
        assertThrows(UnsupportedOperationException::class.java) {
            WebDavFileSystemProvider.newByteChannel(
                path("/file.txt"),
                setOf(StandardOpenOption.READ),
                attribute
            )
        }
    }

    @Test
    fun aLinkTargetFromAnotherProviderIsAMismatch() {
        assertThrows(ProviderMismatchException::class.java) {
            WebDavFileSystemProvider.createSymbolicLink(path("/link"), TestPath("/target"))
        }
        // A bare target passes the check and only then meets what WebDAV cannot do.
        assertThrows(UnsupportedOperationException::class.java) {
            WebDavFileSystemProvider.createSymbolicLink(
                path("/link"),
                ByteStringPath("target".toByteString())
            )
        }
    }

    @Test
    fun aModifiedTimeIsNotSentBecauseServersRefuseIt() {
        server.addFile("/file.txt", "hello")
        WebDavFileSystemProvider
            .getFileAttributeView(path("/file.txt"), BasicFileAttributeView::class.java)!!
            .setTimes(FileTime.fromMillis(0), null, null)
        WebDavFileSystemProvider.copy(
            path("/file.txt"),
            path("/copy.txt"),
            StandardCopyOption.COPY_ATTRIBUTES
        )
        assertEquals("hello", server.fileContent("/copy.txt"))
        assertTrue(server.requests.toString(), server.requests.none { it.startsWith("PROPPATCH") })
    }
}
