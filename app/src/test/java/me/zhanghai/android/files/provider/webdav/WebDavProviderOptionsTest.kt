/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.webdav

import java.nio.ByteBuffer
import java8.nio.file.AccessMode
import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.FileSystemException
import java8.nio.file.LinkOption
import java8.nio.file.NoSuchFileException
import java8.nio.file.NotLinkException
import java8.nio.file.OpenOption
import java8.nio.file.ProviderMismatchException
import java8.nio.file.StandardOpenOption
import java8.nio.file.attribute.FileAttribute
import java8.nio.file.attribute.PosixFilePermissions
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
 * The open options, links and access checks of [WebDavFileSystemProvider]
 * against [FakeWebDavServer]: what the provider does itself before (or instead of) asking the
 * server.
 */
class WebDavProviderOptionsTest {
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

    private fun read(path: String, vararg options: OpenOption): String =
        WebDavFileSystemProvider.newInputStream(path(path), *options)
            .use { String(it.readBytes()) }

    private val posixAttribute: FileAttribute<*> =
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))

    @Test
    fun anInputStreamRefusesEveryWayOfWriting() {
        server.addFile("/file.txt", "hello")
        for (option in listOf(
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND,
            StandardOpenOption.DELETE_ON_CLOSE,
            StandardOpenOption.SYNC,
            StandardOpenOption.DSYNC
        )) {
            assertThrows(option.toString(), UnsupportedOperationException::class.java) {
                WebDavFileSystemProvider.newInputStream(path("/file.txt"), option)
            }
        }
        assertEquals("hello", server.fileContent("/file.txt"))
    }

    /** As in java.nio, the options that only make sense for writing are ignored for reading. */
    @Test
    fun anInputStreamIgnoresTheOptionsForWriting() {
        server.addFile("/file.txt", "hello")
        assertEquals("hello", read("/file.txt", StandardOpenOption.TRUNCATE_EXISTING))
        assertEquals("hello", read("/file.txt", StandardOpenOption.CREATE_NEW))
        assertEquals("hello", server.fileContent("/file.txt"))
        assertThrows(NoSuchFileException::class.java) {
            read("/new.txt", StandardOpenOption.CREATE)
        }
        assertFalse(server.exists("/new.txt"))
    }

    @Test
    fun anInputStreamWithoutFollowingLinksLooksAtTheFileFirst() {
        server.addFile("/file.txt", "hello")
        assertEquals("hello", read("/file.txt", LinkOption.NOFOLLOW_LINKS))
        assertTrue(server.requests.toString(), "PROPFIND /file.txt" in server.requests)
        assertThrows(NoSuchFileException::class.java) {
            read("/missing.txt", LinkOption.NOFOLLOW_LINKS)
        }
    }

    @Test
    fun anInputStreamOfAMissingFileFailsWhenItIsRead() {
        assertThrows(NoSuchFileException::class.java) { read("/missing.txt") }
    }

    @Test
    fun anOutputStreamWithoutOptionsCreatesAndReplaces() {
        server.addFile("/file.txt", "old content")
        WebDavFileSystemProvider.newOutputStream(path("/file.txt"))
            .use { it.write("new".toByteArray()) }
        WebDavFileSystemProvider.newOutputStream(path("/created.txt"))
            .use { it.write("created".toByteArray()) }
        assertEquals("new", server.fileContent("/file.txt"))
        assertEquals("created", server.fileContent("/created.txt"))
    }

    @Test
    fun anOutputStreamMustTruncateOrCreateANewFile() {
        server.addFile("/file.txt", "hello")
        assertThrows(UnsupportedOperationException::class.java) {
            WebDavFileSystemProvider.newOutputStream(path("/file.txt"), StandardOpenOption.WRITE)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            WebDavFileSystemProvider.newOutputStream(
                path("/file.txt"),
                StandardOpenOption.APPEND
            )
        }
        assertEquals("hello", server.fileContent("/file.txt"))
    }

    @Test
    fun anOutputStreamForANewFileLeavesAnExistingOneAlone() {
        server.addFile("/file.txt", "hello")
        assertThrows(FileAlreadyExistsException::class.java) {
            WebDavFileSystemProvider.newOutputStream(
                path("/file.txt"),
                StandardOpenOption.CREATE_NEW
            )
        }
        assertEquals("hello", server.fileContent("/file.txt"))
        WebDavFileSystemProvider.newOutputStream(path("/new.txt"), StandardOpenOption.CREATE_NEW)
            .use { it.write("new".toByteArray()) }
        assertEquals("new", server.fileContent("/new.txt"))
    }

    @Test
    fun anOutputStreamWithoutCreateNeedsTheFileToExist() {
        assertThrows(NoSuchFileException::class.java) {
            WebDavFileSystemProvider.newOutputStream(
                path("/missing.txt"),
                StandardOpenOption.TRUNCATE_EXISTING
            )
        }
        assertFalse(server.exists("/missing.txt"))
    }

    @Test
    fun aFileChannelIsNotSupported() {
        assertThrows(UnsupportedOperationException::class.java) {
            WebDavFileSystemProvider.newFileChannel(
                path("/file.txt"),
                setOf(StandardOpenOption.READ)
            )
        }
        assertThrows(UnsupportedOperationException::class.java) {
            WebDavFileSystemProvider.newFileChannel(
                path("/file.txt"),
                setOf(StandardOpenOption.READ),
                posixAttribute
            )
        }
    }

    @Test
    fun aByteChannelForWritingMustTruncate() {
        server.addFile("/file.txt", "hello")
        assertThrows(UnsupportedOperationException::class.java) {
            WebDavFileSystemProvider.newByteChannel(
                path("/file.txt"),
                setOf(StandardOpenOption.WRITE)
            )
        }
    }

    @Test
    fun aByteChannelOfAMissingFileForWritingNeedsCreate() {
        assertThrows(NoSuchFileException::class.java) {
            WebDavFileSystemProvider.newByteChannel(
                path("/missing.txt"),
                setOf(StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
            )
        }
        assertFalse(server.exists("/missing.txt"))
    }

    @Test
    fun aByteChannelForANewFileFailsForAnExistingOne() {
        server.addFile("/file.txt", "hello")
        assertThrows(FileAlreadyExistsException::class.java) {
            WebDavFileSystemProvider.newByteChannel(
                path("/file.txt"),
                setOf(
                    StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.TRUNCATE_EXISTING
                )
            )
        }
        assertEquals("hello", server.fileContent("/file.txt"))
    }

    @Test
    fun aByteChannelForANewFileCreatesItFirst() {
        WebDavFileSystemProvider.newByteChannel(
            path("/file.txt"),
            setOf(
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            )
        ).use { channel ->
            assertEquals(3, channel.write(ByteBuffer.wrap("abc".toByteArray())))
        }
        assertEquals("abc", server.fileContent("/file.txt"))
        // The file is created empty before anything is written to it.
        val puts = server.requests.filter { it == "PUT /file.txt" }
        assertEquals(server.requests.toString(), 2, puts.size)
    }

    @Test
    fun aByteChannelCannotWriteOutOfOrderToAServerWithoutPartialUpdates() {
        server.addFile("/file.txt", "hello")
        WebDavFileSystemProvider.newByteChannel(
            path("/file.txt"),
            setOf(StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
        ).use { channel ->
            channel.position(2)
            assertThrows(java.io.IOException::class.java) {
                channel.write(ByteBuffer.wrap("x".toByteArray()))
            }
        }
    }

    @Test
    fun aByteChannelCanOnlyBeTruncatedToNothing() {
        server.addFile("/file.txt", "hello")
        WebDavFileSystemProvider.newByteChannel(
            path("/file.txt"),
            setOf(
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
            )
        ).use { channel ->
            assertThrows(java.io.IOException::class.java) { channel.truncate(1) }
        }
    }

    @Test
    fun aByteChannelRefusesFileAttributes() {
        server.addFile("/file.txt", "hello")
        assertThrows(UnsupportedOperationException::class.java) {
            WebDavFileSystemProvider.newByteChannel(
                path("/file.txt"),
                setOf(StandardOpenOption.READ),
                posixAttribute
            )
        }
    }

    @Test
    fun aDirectoryIsCreatedWithMkcolAndOnlyOnce() {
        WebDavFileSystemProvider.createDirectory(path("/dir"))
        assertTrue(server.exists("/dir"))
        assertThrows(FileSystemException::class.java) {
            WebDavFileSystemProvider.createDirectory(path("/dir"))
        }
        assertThrows(UnsupportedOperationException::class.java) {
            WebDavFileSystemProvider.createDirectory(path("/other"), posixAttribute)
        }
        assertFalse(server.exists("/other"))
    }

    @Test
    fun linksCannotBeCreated() {
        val link = path("/link")
        assertThrows(UnsupportedOperationException::class.java) {
            WebDavFileSystemProvider.createSymbolicLink(link, path("/target"))
        }
        assertThrows(UnsupportedOperationException::class.java) {
            WebDavFileSystemProvider.createSymbolicLink(
                link,
                ByteStringPath("target".toByteString())
            )
        }
        assertThrows(ProviderMismatchException::class.java) {
            WebDavFileSystemProvider.createSymbolicLink(link, TestPath("/target"))
        }
        assertThrows(UnsupportedOperationException::class.java) {
            WebDavFileSystemProvider.createSymbolicLink(link, path("/target"), posixAttribute)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            WebDavFileSystemProvider.createLink(link, path("/target"))
        }
    }

    @Test
    fun anOrdinaryFileIsNotALink() {
        server.addFile("/file.txt", "hello")
        val exception = assertThrows(NotLinkException::class.java) {
            WebDavFileSystemProvider.readSymbolicLink(path("/file.txt"))
        }
        assertEquals("/file.txt", exception.file)
        assertThrows(NoSuchFileException::class.java) {
            WebDavFileSystemProvider.readSymbolicLink(path("/missing"))
        }
    }

    @Test
    fun onlyReadAccessCanBeCheckedAndOnlyForWhatExists() {
        server.addFile("/file.txt", "hello")
        WebDavFileSystemProvider.checkAccess(path("/file.txt"))
        WebDavFileSystemProvider.checkAccess(path("/file.txt"), AccessMode.READ)
        assertThrows(NoSuchFileException::class.java) {
            WebDavFileSystemProvider.checkAccess(path("/missing.txt"), AccessMode.READ)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            WebDavFileSystemProvider.checkAccess(path("/file.txt"), AccessMode.WRITE)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            WebDavFileSystemProvider.checkAccess(path("/file.txt"), AccessMode.EXECUTE)
        }
    }
}
